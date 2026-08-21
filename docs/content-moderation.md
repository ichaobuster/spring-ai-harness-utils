# 输入与输出内容审核

Spring AI Harness Utils 提供 `InputModerationAdvisor` 和 `OutputModerationAdvisor`，用于在
`ChatClient` Advisor 链中审核用户输入和模型输出。这两个 Advisor 使用 Spring AI 的
`ModerationModel` 接口，因此不依赖具体的审核服务提供商。

- `InputModerationAdvisor` 在请求发送给 `ChatModel` 之前审核最近的用户消息。
- `OutputModerationAdvisor` 审核非流式调用和流式调用中的 assistant 输出。
- 非流式调用通过异常报告违规；流式调用通过终止响应报告违规。

有关 Spring AI 审核模型和 Advisor 链的基础概念，请参阅 Spring AI Reference 中的
[Moderation Models](https://docs.spring.io/spring-ai/reference/api/moderation.html) 和
[Chat Client API](https://docs.spring.io/spring-ai/reference/api/chatclient.html)。

## 前置条件

应用必须提供一个 Spring AI `ModerationModel` Bean。审核服务的依赖、凭据和模型配置由具体提供商决定。

以 OpenAI 为例，可以添加 Spring AI OpenAI Starter：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

然后配置审核模型：

```yaml
spring:
  ai:
    model:
      moderation: openai
    openai:
      api-key: ${OPENAI_API_KEY}
      moderation:
        options:
          model: omni-moderation-latest
```

> [!NOTE]
> 上述属性结构适用于本项目使用的 Spring AI 1.1.8。其他 Spring AI 版本可能调整提供商属性名称，
> 升级时应以对应版本的配置元数据和 Reference 文档为准。

有关提供商配置的完整说明，请参阅 Spring AI Reference 中对应的审核模型文档。例如，
[OpenAI Moderation](https://docs.spring.io/spring-ai/reference/api/moderation/openai-moderation.html)。

## 添加项目依赖

建议通过 Spring AI Harness Utils BOM 管理版本：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.spring-ai.harness</groupId>
            <artifactId>spring-ai-harness-utils-bom</artifactId>
            <version>${spring-ai-harness.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>io.github.spring-ai.harness</groupId>
        <artifactId>spring-ai-harness-utils</artifactId>
    </dependency>
</dependencies>
```

Gradle 项目可以使用平台依赖：

```groovy
dependencies {
    implementation platform("io.github.spring-ai.harness:spring-ai-harness-utils-bom:${springAiHarnessVersion}")
    implementation "io.github.spring-ai.harness:spring-ai-harness-utils"
}
```

## 快速开始

以下示例创建输入和输出审核 Advisor，并将它们注册为 `ChatClient` 的默认 Advisor：

```java
@Configuration
public class ModerationConfiguration {

    @Bean
    InputModerationAdvisor inputModerationAdvisor(ModerationModel moderationModel) {
        return InputModerationAdvisor.builder(moderationModel).build();
    }

    @Bean
    OutputModerationAdvisor outputModerationAdvisor(ModerationModel moderationModel) {
        return OutputModerationAdvisor.builder(moderationModel).build();
    }

    @Bean
    ChatClient chatClient(ChatClient.Builder builder,
            InputModerationAdvisor inputModerationAdvisor,
            OutputModerationAdvisor outputModerationAdvisor) {
        return builder
            .defaultAdvisors(inputModerationAdvisor, outputModerationAdvisor)
            .build();
    }

}
```

两个 Advisor 的单次审核上限默认都是 5,000 个字符。这里的“字符”和本文中的所有偏移量均按
Java `String.length()` 计算，即 UTF-16 code unit，而不是 Unicode code point 或模型 token。

## 输入审核

`InputModerationAdvisor` 只审核 `UserMessage` 的文本。它从最新消息开始向前填充字符预算，
以覆盖用户将不安全内容拆分到多条消息中的情况。

输入文本按以下规则收集：

1. 忽略 system、assistant 和 tool 消息。
2. 从最新的 `UserMessage` 开始向历史消息回溯。
3. 消息之间使用换行符连接，换行符计入字符预算。
4. 如果边界消息不能完整放入预算，则保留该消息的尾部。
5. 提交审核前恢复消息的时间顺序。

例如，最大审核字符数为 12 时，以下用户消息：

```text
[User] earlier-content
[Assistant] ignored
[User] latest
```

将优先保留最新消息，并使用剩余预算保留上一条用户消息的尾部。最终提交给
`ModerationModel` 的内容仍按原始时间顺序排列。

### 输入违规处理

非流式调用审核不通过时，Advisor 抛出 `ModerationViolationException`：

```java
try {
    String content = chatClient.prompt()
        .user(userText)
        .call()
        .content();
}
catch (ModerationViolationException ex) {
    if (ex.getStage() == ModerationViolationException.Stage.INPUT) {
        // 返回适合应用场景的错误信息
    }
}
```

流式调用审核不通过时不会调用下游 `ChatModel`。Advisor 直接返回一个终止响应：

- generation `finishReason` 为 `content_filter`；
- assistant metadata 包含 `stop=true`；
- assistant metadata 包含稳定的 `moderation_error` 消息；
- assistant 正文为空。

## 输出审核

### 非流式调用

对于非流式调用，`OutputModerationAdvisor` 会审核 `ChatResponse` 中每个 assistant generation 的完整文本。
长文本使用重叠窗口拆分，任一窗口返回 `flagged=true` 即视为输出违规。

默认窗口参数如下：

| 参数 | 默认值 | 说明 |
| --- | ---: | --- |
| 最大窗口 | 5,000 | 单次发送给 `ModerationModel` 的最大字符数 |
| 窗口步长 | 4,500 | 每个后续窗口最多引入的新字符数 |
| 重叠上下文 | 500 | 相邻窗口共享的历史字符数 |

窗口边界会在容量允许时避免拆开 UTF-16 surrogate pair。

非流式调用的任一 generation 审核不通过时，Advisor 抛出 stage 为
`ModerationViolationException.Stage.OUTPUT` 的 `ModerationViolationException`。

### 流式调用

流式审核为每次订阅创建独立状态，并按 generation 分别累计文本。generation 优先使用响应
metadata 中的 `index` 标识；缺失或无法解析时使用该响应中的 ordinal。provider index 和
ordinal fallback 在内部使用不同的身份空间，避免相同整数值造成 generation 内容串线。

满足以下任一条件时会审核当前所有待审核 generation：

- 任一 generation 的新增文本达到字符间隔，默认 4,500；
- 收到的源 `ChatClientResponse` 达到 chunk 间隔，默认 100；
- 任一 generation 出现 finish reason；
- 上游正常结束。

> [!NOTE]
> chunk 间隔按源 `ChatClientResponse` 数量计算，而不是按 token、字符或 Reactor 请求次数计算。
> 流结束时会强制审核不足一个间隔的尾部内容。

每次审核通过后，Advisor 最多保留最大字符数的 10% 作为下一批的历史上下文。默认配置下，
下一批审核会携带最多 500 个已审核字符。单个超大 chunk 仍会在内部拆分为不超过最大字符数的
重叠窗口。

## 流式审核模式

`OutputModerationAdvisor` 提供两种流式审核模式。

### RELEASE_FIRST

`RELEASE_FIRST` 是默认模式。非终态响应先发送给客户端，然后串行执行审核。审核期间 Advisor
暂停继续拉取上游；finish 响应会暂缓到尾部审核完成后再发送。

这种模式优先降低正常输出的延迟，但不能保证客户端从未接收到违规内容。最坏情况下，客户端可能在
发现违规前收到“配置的字符间隔加一个 provider chunk”的内容。

> [!WARNING]
> 如果应用不能接受任何未经审核的输出，请使用 `MODERATE_FIRST`。不要将 `RELEASE_FIRST`
> 解释为严格的内容隔离边界。

### MODERATE_FIRST

`MODERATE_FIRST` 缓存当前批次的原始响应。只有当批次内所有 generation 审核通过后，才会按
原始顺序发送这些响应。当前批次审核不通过时，该批次的正文不会发送。

由于相邻批次包含重叠上下文，后一批可能因为包含上一批边界文本而被判定违规。此时上一批内容可能
已经发送。客户端可以结合终止响应中的窗口区间和最后安全位置清理已显示内容。

配置 `MODERATE_FIRST`：

```java
OutputModerationAdvisor outputModerationAdvisor = OutputModerationAdvisor.builder(moderationModel)
    .streamModerationMode(OutputModerationAdvisor.StreamModerationMode.MODERATE_FIRST)
    .build();
```

## 流式终止响应

检测到违规后，Advisor 会取消上游订阅，并只发送一个不包含违规正文、工具调用或媒体的终止响应。
该响应保留源 `ChatResponse` metadata 和 `ChatClientResponse` context。

generation metadata 中的 `finishReason` 为 `content_filter`。assistant metadata 包含以下字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `stop` | `boolean` | 固定为 `true`，表示审核已终止流 |
| `moderation_error` | `String` | 稳定的审核拒绝消息 |
| `moderation_generation_index` | `int` | 命中审核窗口的 generation index |
| `moderation_window_start` | `long` | 命中审核窗口在该 generation 中的起始偏移，包含 |
| `moderation_window_end` | `long` | 命中审核窗口在该 generation 中的结束偏移，不包含 |
| `moderation_safe_through` | `long` | 此前已完整审核通过的全局结束偏移 |

`moderation_window_start` 和 `moderation_window_end` 描述被审核模型判定违规的整个窗口，
不是精确的违规词位置。输入审核的终止响应不包含 generation 和窗口偏移字段。

客户端可以按以下方式识别终止响应：

```java
chatClient.prompt()
    .user(userText)
    .stream()
    .chatResponse()
    .doOnNext(response -> {
        Generation generation = response.getResult();
        AssistantMessage output = generation.getOutput();
        boolean stopped = Boolean.TRUE.equals(output.getMetadata().get("stop"));
        if (stopped) {
            String reason = generation.getMetadata().getFinishReason();
            Object error = output.getMetadata().get("moderation_error");
            // 停止渲染，并按应用协议通知客户端
        }
    })
    .subscribe();
```

## 配置选项

### InputModerationAdvisor

| Builder 方法 | 默认值 | 说明 |
| --- | ---: | --- |
| `maxModerationCharacters(int)` | `5000` | 输入审核的最大字符数，必须大于 0 |
| `observationRegistry(ObservationRegistry)` | `ObservationRegistry.NOOP` | 用于记录审核模型异常的 Observation Registry |
| `order(int)` | `Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 10` | Advisor 顺序 |

### OutputModerationAdvisor

| Builder 方法 | 默认值 | 说明 |
| --- | ---: | --- |
| `maxModerationCharacters(int)` | `5000` | 单次审核的最大字符数，必须大于 0 |
| `streamModerationCharacterInterval(int)` | `max(1, floor(最大字符数 × 90%))` | 流式字符触发间隔，必须大于 0 且不超过内部计算的窗口步长 |
| `streamModerationChunkInterval(int)` | `100` | 流式响应 chunk 触发间隔，必须大于 0 |
| `streamModerationMode(StreamModerationMode)` | `RELEASE_FIRST` | 流式批次的释放策略 |
| `observationRegistry(ObservationRegistry)` | `ObservationRegistry.NOOP` | 用于记录审核模型异常的 Observation Registry |
| `order(int)` | `Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 130` | Advisor 顺序 |

所有组合约束都会在 `build()` 时统一校验，因此 Builder 方法的调用顺序不会影响校验结果。例如：

```java
OutputModerationAdvisor outputModerationAdvisor = OutputModerationAdvisor.builder(moderationModel)
    .maxModerationCharacters(2_000)
    .streamModerationCharacterInterval(1_800)
    .streamModerationChunkInterval(50)
    .streamModerationMode(OutputModerationAdvisor.StreamModerationMode.MODERATE_FIRST)
    .order(Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 130)
    .build();
```

## Advisor 顺序

Advisor 按 `getOrder()` 的值从小到大处理请求，并以相反顺序处理响应。默认情况下：

- 输入审核位于 ChatMemory Advisor 之后，因此可以审核 ChatMemory 展开的用户消息历史。
- 输出审核在响应链返回时先于更低 `order` 的响应后处理 Advisor 执行，从而审核原始 assistant 文本。

如果自定义 `order`，应确保输入审核仍位于需要扩展用户历史的 Advisor 之后，并确保输出审核在脱敏、
重写或截断 assistant 文本之前看到原始输出。

## 失败处理与可观测性

审核模型调用抛出运行时异常，或返回缺少必要结果的响应时，两个 Advisor 都采用 fail-open 策略：

1. 发生异常的审核窗口被视为通过；存在其他窗口时继续审核，原调用或流不会仅因模型异常而终止。
2. 使用 SLF4J `WARN` 记录审核阶段和异常堆栈。
3. 日志不包含待审核原文。
4. 如果配置的 `ObservationRegistry` 存在当前 Observation，则调用 `error(exception)`。

将应用使用的 `ObservationRegistry` 传给 Advisor：

```java
InputModerationAdvisor inputModerationAdvisor = InputModerationAdvisor.builder(moderationModel)
    .observationRegistry(observationRegistry)
    .build();

OutputModerationAdvisor outputModerationAdvisor = OutputModerationAdvisor.builder(moderationModel)
    .observationRegistry(observationRegistry)
    .build();
```

当 Micrometer 与 OpenTelemetry bridge 已配置时，当前 Observation 的错误会反映到关联 span。有关
Spring AI 可观测性的基础配置，请参阅
[Observability](https://docs.spring.io/spring-ai/reference/observability/index.html)。

> [!IMPORTANT]
> fail-open 可以避免审核服务故障扩大为聊天服务故障，但它不适合作为严格合规场景中的唯一安全边界。
> 如果业务要求审核服务不可用时拒绝内容，应在应用层增加 fail-closed 策略或其他独立防护。

## 设计限制

- 审核结果只使用 `flagged` 总标志，不公开提供商特定的分类或分数。
- 字符预算和偏移量使用 UTF-16 code unit，不等同于用户可见字符数或模型 token 数。
- `RELEASE_FIRST` 允许有限的未审核内容先行释放。
- `MODERATE_FIRST` 只保证当前违规批次不释放；跨批重叠窗口可能重新命中上一批边界内容。
- 流式审核不实现后台并行审核或安全 checkpoint chunk，以避免无界在途内容和额外客户端协议。
