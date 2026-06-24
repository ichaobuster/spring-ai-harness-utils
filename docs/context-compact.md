# Context Compact (上下文压缩)

随着对话深度的增加和复杂工具的调用，发送给对话模型（LLM）的上下文 Token 数量会快速膨胀。  
为了对输入上下文进行合理管理，避免超出 LLM 的上下文窗口（Context Window）限制，在保护核心提问意图的前提下降低资源消耗，Spring AI Bocom 参考了 `Claude Code` 的源码实现了上下文压缩（Context Compact）机制。

这套压缩机制是基于 Spring AI `ChatClient` 和 `Advisor` 体系的非侵入式、多阶段拦截和清理流水线。通过四个核心 Advisor 从不同维度递进式地缩减历史对话内容。

## 概览与执行顺序

为确保内容被正确压缩而不会相互干扰，系统的不同 Advisor 在拦截链上存在逻辑上的先后依赖。在将其注册到 `ChatClient` 的 `AdvisorChain` 时，通过 `order` 属性控制 `Advisor` 的执行顺序至关重要（数字越小越优先执行）：

1. **ToolResultBudgetAdvisor**: 压缩工具调用结果 Tool Result 的长度。
2. **MicroCompactAdvisor**: 淘汰早期对话中工具调用结果 Tool Result。
3. **ClearThinkingAdvisor**: 移除早期对话中「深度思考」内容。
4. **AutoCompactAdvisor**: 全局重写机制，用于当所有轻量方案执行后，预估 Token 依然超限时对原上下文进行终极浓缩总结。
5. **ToolArgsCompactAdvisor**: 模仿 AgentScope 的**预压缩参数截断**机制，压缩`Write`、`Edit`等可能存在超长工具入参的情况下的工具参数（实验性，目前暂未证实对入参的压缩是否会影响LLM后续表现）

> !IMPORTANT
> 1. 上述五个 Advisor 的默认 `order` 已经严格按照以上推荐的流水线拦截顺序进行了内置。在大多数情况下，为了保证压缩逻辑和预判的正确性，请直接使用默认配置顺序；如果没有特殊需求，请勿修改 `order` 属性。
> 2. 需要与 `ToolCallAdvisor` 配合使用
> 3. 对于需要保存完整消息记录的场景，建议与 `AdvancedMessageChatMemoryAdvisor` 配合使用

---

## 1. ToolResultBudgetAdvisor (工具结果预算控制)

`ToolResultBudgetAdvisor` 主要为了应对大文本读取、RAG召回大量文本内容等容易瞬间“撑爆”上下文窗口的 Tool Call 结果。它并不会武断地删除内容，而是借助 Redis 对超出 Token 预算的结果进行内容剥离。

### 原理设计
它将连续的 Tool 消息视为一组（以 Assistant 消息为分隔边界）。在执行前，对该组内容进行统计评估：
- **双重阈值判断**：若组内工具结果总字符串超过“组阈值（默认 200,000 字符）”，或某条单一工具响应大小超出“单条阈值（默认 50,000 字符）”，即将其内容截断，并在 Redis 设置关联储存；
- **占位替换**：对于被抽离的内容，上下文中将存留一部分头部摘要，同时将全量数据存储至 Redis 后、将 Redis Key 提供给大模型作为后续可通过 `ToolResultBudgetTool` 获取全量数据的凭证。

**消息流转示意图：**
```text
🔍 【处理前 Context】
[System]     你是一个代码分析助手...
[User]       帮我分析一下这组报错日志。
[Assistant]  [ToolCall: fetch_error_logs]          <- 发起对日志查询工具的调用
[Tool]       [日志内容开始：Exception in thread...     <- 拉取到了数十万字的巨大字符文本
              ...............................]     <- (内容严重超出单条上限设置)
[Assistant]  (模型根据拉取的日志继续进行分析...)

                 ⬇️ 容量剥离与凭证替换 ⬇️

💡 【处理后 Context】
[System]     你是一个代码分析助手...
[User]       帮我分析一下这组报错日志。
[Assistant]  [ToolCall: fetch_error_logs]
[Tool]       <persisted-output> 日志内容开始...       <- 头部摘要截断保留
              [原完整输出已存入 Redis, Key: xxx]    <- 凭证引导大模型反向找回原文
[Assistant]  (模型根据拉取的日志继续进行分析...)
```

### 使用示例

```java
ToolResultBudgetAdvisor budgetAdvisor = ToolResultBudgetAdvisor.builder(redisTemplate)
    .maxSingleResultChars(50_000)         // 单条工具调用结果字符阈值
    .maxPerGroupBudgetChars(200_000)      // 一组连续工具调用的结果总字符阈值
    .ttl(Duration.ofDays(1))             // 暂存 Redis 的 TTL 时间
    .skipToolName("toolResultBudgetTool") // 免检工具名单
    .previewSize(2000)  // 压缩后的预览文本长度
    .chatMemory(chatMemory)  // 存储压缩后的sessions
    .build();
ToolCallback toolCallback = ToolResultBudgetTool.createToolCallback(redisTemplate);

ChatClient chatClient = ChatClient.builder(chatModel)
    .defaultToolCallbacks(toolCallback)
    .defaultAdvisors(budgetAdvisor)
...
```

> !NOTE
> 包含在此免检名单 (`skipToolName`) 中的工具一般用来提取和找回已被外置缓存的原始信息副本。由于它们旨在为 LLM 逆向追溯详情，故自身必须豁免检查处理。

也可单独使用 `ToolResultBudgetTool` 工具用于读取超预算工具调用的结果：
```java
ToolCallback toolCallback = ToolResultBudgetTool.createToolCallback(redisTemplate);
```

### 备注

由于 gradle 的限制，无法正常产生带有 `optional` 字段的 pom 文件，因此，需要使用 `ToolResultBudgetAdvisor`
时，请单独添加 `spring-data-redis` 依赖。
请在 Maven 项目中的 `pom.xml` 添加以下依赖：

```xml

<dependency>
    <groupId>org.springframework.data</groupId>
    <artifactId>spring-data-redis</artifactId>
    <version>${springboot.version}</version>
</dependency>
```  

或在 Gradle 项目中的 `depenencies.gradle` 或 `build.gradle` 中添加以下依赖：

```groovy
dependencies {
    implementation 'org.springframework.data:spring-data-redis:$springbootVersion'
}
```

---

## 2. MicroCompactAdvisor (淘汰陈旧的工具执行结果)

与上一步将大文本提取暂存不同，`MicroCompactAdvisor` 的核心理念是“断舍离”。  
它基于一个假设：对于用户最新的指令，很久以前的工具执行结果将因为缺乏时效性等原因失去参考价值。  
基于上述假设，`MicroCompactAdvisor` 将经过特定判定条件后对符合条件的工具调用结果内容移除来空出上下文窗口。

### 原理设计
它针对使用者指定的工具目标（`compactableToolNames`），采取混合的丢弃触发机制。被遗忘淘汰的工具会在上下文被替换为 `[Old tool result content cleared]` 提示，以此告知大模型该细节已被遗弃。

**消息流转示意图：**
```text
🔍 【处理前 Context】
[System]     你是一个天气播报助手...
[User]       查询一下上海的天气。
[Assistant]  [ToolCall: query_weather] 
[Tool]       {"city": "上海", "weather": "雨"}      <- 较早发出的历史查询调用
[Assistant]  上海今天有雨。
[User]       现在帮我查一下北京的。
[Assistant]  [ToolCall: query_weather] 
[Tool]       {"city": "北京", "weather": "晴"}      <- 最近发出的一次调用
[Assistant]  北京今天天气晴朗。

                 ⬇️ 触发清理 ⬇️

💡 【处理后 Context】
[System]     你是一个天气播报助手...
[User]       查询一下上海的天气。
[Assistant]  [ToolCall: query_weather] 
[Tool]       [Old tool result content cleared]     <- 历史 Tool 记录被彻底无害化清空
[Assistant]  上海今天有雨。
[User]       现在帮我查一下北京的。
[Assistant]  [ToolCall: query_weather]             
[Tool]       {"city": "北京", "weather": "晴"}      <- 因为距离较近所以被豁免保留 
[Assistant]  北京今天天气晴朗。
```

### 使用示例

```java
MicroCompactAdvisor microCompactAdvisor = MicroCompactAdvisor.builder()
    .compactableToolNames("fetch_web_content", "query_database") // 允许被淘汰回收的工具名
    .triggerThreshold(10) // 达到 10 条上述工具产生的 Tool Result 时触发预警，将执行淘汰操作
    .keepRecent(5)        // 清理行为一旦触发，总是保留最近的 5 条工具调用结果 Tool Result
    .keepResultLength(0) // 不进行压缩的工具结果长度，默认为0，即不论结果多长都压缩
    .chatMemory(chatMemory)  // 存储压缩后的sessions
    .build();
```

---

## 3. ClearThinkingAdvisor (清理中间思考过程)

现今具备深度思考的大模型会携回带有思维链推演节点的超大段预判断文本（一般存在于 `</think>` 之前的片段）。  
这些长篇大论对解释当前反馈结果极具价值，但伴随对话轮次增多，越是过往的思考内容越是缺少解决用户最新问题的价值。

### 原理设计
它用于遍历搜索并过滤掉老旧 `AssistantMessage` 回复里的思考内容。

**消息流转示意图：**
```text
🔍 【处理前 Context】
[System]     你是一个架构师...
[User]       我的系统存在性能瓶颈，如何优化这段代码？
[Assistant]  <think>                               <- 开启思维链节点
              分析这段代码。这是一个 O(N^2) 的循环...   
              其实可以通过 HashMap 缓存...
              然后只需要 O(N) 遍历即可...
             </think>                              <- 结束思维链节点
             优化方案：您可以考虑使用 HashMap 来降低复杂度。

                 ⬇️ 深度推演冗余流被切除 ⬇️

💡 【处理后 Context】
[System]     你是一个架构师...
[User]       我的系统存在性能瓶颈，如何优化这段代码？
[Assistant]  优化方案：您可以考虑使用 HashMap 来降低复杂度。 <- (冗长中间黑盒被去除并节省 Token，仅保留有效输出)
```

### 使用示例

```java
ClearThinkingAdvisor clearThinkingAdvisor = ClearThinkingAdvisor.builder()
    .keepRecent(1)           // 只保留最新鲜 1 条附有思维链的消息，其他的清理
    .chatMemory(chatMemory)  // 存储压缩后的sessions
    .build();
```

---

## 4. AutoCompactAdvisor (全局自动化总结压缩)

如果上下文熬过了上三轮层级过滤网，总体量最终预估仍突破了临界线限制。那么流水线将触发最耗费资源但也是最彻底的一层防护网络。

### 原理设计
在发出提向前，Advisor 开始对当前指令全集执行粗略的字符数换算（以 字符数 / 4 近似映射）：
- 一旦粗估体积达到上下文警戒线水位（`contextWindow - maxOutputTokens - autoCompactBufferTokens`），将会通过独立的 `ChatModel` 对全体历史会话做出结构性高度压缩总结。
- **对话线替换**：一旦新摘要通过重重考验诞生（连续失败也会存在熔断容错处理），历史的数十句车轱辘话会被暴力剥离并缩写成一条边界标记（System边界提示）以及一条伪造装载上承前文下接后置任务流向的 `UserMessage` 进行衔接提交。

**消息流转示意图：**
```text
🔍 【处理前 Context (经过多轮深度交互后严重超限队伍)】
[System]     你是一个可以规划旅行的高级助手...
[User]       我们第一天去哪？
[Assistant]  [ToolCall: search_map] 
[Tool]       ...搜索结果反馈...
.......... (这中间经历了数十次的 ReAct 查询与对话，Token 即将超载) ..........
[User]       那最后一天我们就在酒店休息吧。

                 ⬇️ 强制切断提线，触发全局独立微模型摘要浓缩 ⬇️

💡 【处理后 Context】
[System]     [Auto-compact: 50 messages compressed into summary]  <- 隔离段提示，标识此前历史已历经压缩
[User]       [前情提要] 
             这是之前长对话的梳理归纳：
             1. 第一天已经确定去 xxx ...
             2. 中间预订了 xxx 酒店...
             
             (免询问指令要求接手后续工作，继续完成最后一天的回复)         <- 伪造一条 user 请求强制恢复原上下文业务执行流
```

### 使用示例

```java
AutoCompactAdvisor autoCompactAdvisor = AutoCompactAdvisor.builder(compactChatModel)
    .contextWindow(200_000) // 大模型上下文窗口大小，用于预估压缩警戒线
    .maxOutputTokens(20_000)  // 大模型输出 Token 数上限，用于预估压缩警戒线
    .autoCompactBufferTokens(13_000) // 用于执行压缩的大模型最大输出 token 数（预算），用于预估压缩警戒线，用于预估压缩警戒线
    .maxConsecutiveFailures(3)       // 连续 3 次压缩处理失败后就启动自我熔断器
    .chatMemory(chatMemory)  // 存储压缩后的sessions
    .build();
```

> !WARNING  
> 任何 AutoCompact 的压缩和重建本质上就是不可逆的有损缩编。这种通过 LLM 自行决策提取的工作极大受控于外挂模型的自然语义判断偏差。所以务必保障其放置于链条底端最后触发（`order` 值设置最大），作为迫不得已才会使用的最终兜底防护。

> !NOTE
> 如果对压缩结果不满意，可通过 `customInstructions` 追加额外的压缩 prompt，
> 或通过 `baseCompactPrompt` 直接替换整个压缩 prompt。
> 不要直接使用 `AutoCompactAdvisor` 实例！需要通过 `advisorSpecConsumer` 获取 `Consumer<ChatClient.AdvisorSpec>` ，其创建了 `advisorContext` 中用于记录调用情况的对象。
> 如果直接使用 `AutoCompactAdvisor` 实例将造成 compact 失败的自我熔断机制失效。

---

## 5. ToolArgsCompactAdvisor (淘汰陈旧的超长工具参数)

与 `MicroCompactAdvisor` 的核心理念相似，但压缩的目标从工具调用结果改为了工具调用参数。  
它基于一个假设：对于用户最新的指令，很久以前的工具参数将因为缺乏时效性等原因失去参考价值。  
基于上述假设，`ToolArgsCompactAdvisor` 将经过特定判定条件后对符合条件的工具参数移除来空出上下文窗口。

### 原理设计
它针对参数类型为 *String* 且长度大于特定值（默认为2000）的工具参数进行丢弃。被遗忘淘汰的工具参数会截取前20个字符作为预览内容，后续部分替换为 `...(argument truncated)` 提示，以此告知大模型该细节已被遗弃。

**消息流转示意图：**
```text
🔍 【处理前 Context】
[System]     你是一个编码助手...
[User]       写代码分析两个csv，计算10年间销量同比和环比的变化趋势。
[Assistant]  [ToolCall: Write {"path": "/tmp/csv_helper.py", "data": "import sys\nimport pandas\n（大于2000字符的代码）"}]  <- 较早发出的历史查询调用
[Tool]       文件写入 /tmp/csv_helper.py 成功    
[Assistant]  [ToolCall: Bash {"command": "python /tmp/csv_helper.py"}] <- 最近发出的一次调用
[Tool]       分析结果...
[Assistant]  以下是分析比较结果....

                 ⬇️ 触发清理 ⬇️

💡 【处理后 Context】
[System]     你是一个编码助手...
[User]       写代码分析两个csv，计算10年间销量同比和环比的变化趋势。
[Assistant]  [ToolCall: Write {"path": "/tmp/csv_helper.py", "data": "import sys\nimport pa...(argument truncated)"}]  <- 历史 Tool 长参数记录被清空
[Tool]       文件写入 /tmp/csv_helper.py 成功    
[Assistant]  [ToolCall: Bash {"command": "python /tmp/csv_helper.py"}]  <- 因为距离较近且没有大于2000的参数所以被豁免保留 
[Tool]       分析结果...
[Assistant]  以下是分析比较结果....
```

### 使用示例

```java
ToolArgsCompactAdvisor toolArgsCompactAdvisor = ToolArgsCompactAdvisor.builder()
    .triggerMessages(10)   // 当 assistant messages 总数超过此值时触发清除操作
    .keepRecent(5)         // 清理行为一旦触发，总是保留最近的 5 条 assistant messages 不做清理
    .maxArgLength(2000)    // 不进行压缩的工具参数长度，默认为2000
    .truncationText("...(argument truncated)")  // 已被清除的 tool args 内容超过20字符长度后替换为此文本
    .chatMemory(chatMemory)  // 存储压缩后的sessions
    .build();
```

---

## 推荐集成模式

对于真实场景接入，可选择全部或部分 Advisor 添加到 `ChatClient` 中使用，并需要与 `ToolCallAdvisor` 配合使用；对于需要保存完整消息记录的场景，建议与 `SaveChatMemoryAdvisor` 配合使用（**注意：以下省略了部分配置细节，详细参数请务必参照前文**）：

```java
@Configuration
public class ChatClientConfiguration {

    @Bean
    public ChatClient customChatClient(ChatClient.Builder builder, 
                                       StringRedisTemplate redisTemplate,
									   ChatMemory chatMemory,
                                       @Qualifier("compactModel") ChatModel compactModel) {
                                       
        return builder
            .defaultAdvisors(
                ToolCallAdvisor.builder().build(),
                ToolResultBudgetAdvisor.builder(redisTemplate).chatMemory(chatMemory).build(),
                MicroCompactAdvisor.builder().chatMemory(chatMemory).build(),
                ClearThinkingAdvisor.builder().chatMemory(chatMemory).build(),
                ToolArgsCompactAdvisor.builder().chatMemory(chatMemory).build(),
            )
            .defaultAdvisors(AutoCompactAdvisor.builder(compactModel).chatMemory(chatMemory).build().advisorSpecConsumer())
            .defaultToolCallbacks(ToolResultBudgetTool.createToolCallback(redisTemplate))
            .build();
    }
}
```

引入 Context Compact 后，开发人员即可拥有高度自动化且分层的窗口缓冲屏障，应用将能在深度交互业务和长时间驻留的复杂 Agent 对话场景中保证极高的稳定性和响应健康度。
