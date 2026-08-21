# C2 数据识别与脱敏

Spring AI Harness Utils 提供 C2 类敏感数据的识别和脱敏能力，可用于处理工具调用参数以及大语言模型返回的 Assistant Message。

该功能由以下三个核心组件组成：

| 组件 | 作用 |
| --- | --- |
| `C2DataMaskingService` | 识别并脱敏文本中的电话号码、18 位中国大陆身份证号、银行卡号和邮箱支付账号 |
| `C2ToolArgumentsMaskingAdvisor` | 在工具执行前递归脱敏工具参数 JSON 中的字符串值 |
| `C2AssistantMessageMaskingAdvisor` | 脱敏 Assistant Message 文本，同时支持非流式和流式响应 |

这些组件不依赖 Spring 组件扫描或自动配置。应用需要显式创建 `C2DataMaskingService`，并将同一个实例传递给两个 Advisor，以确保它们使用一致的识别规则和掩码字符。

## 添加依赖

将 `spring-ai-harness-utils` 添加到项目依赖：

```xml
<dependency>
    <groupId>io.github.spring-ai.harness</groupId>
    <artifactId>spring-ai-harness-utils</artifactId>
    <version>${spring-ai-harness.version}</version>
</dependency>
```

电话号码识别所需的 Google libphonenumber 已由该模块传递引入，无需单独声明。

## C2DataMaskingService

`C2DataMaskingService` 是识别和脱敏功能的核心入口。默认 Builder 会装配全部内置 Recognizer，并使用 `*` 作为掩码字符。

### 快速开始

```java
C2DataMaskingService maskingService = C2DataMaskingService.builder()
        .build();

String text = "联系人手机号为 13800138000，邮箱为 alice@example.com";

List<C2DataMatch> matches = maskingService.detect(text);
String maskedText = maskingService.mask(text);
```

`maskedText` 的结果如下：

```text
联系人手机号为 138****8000，邮箱为 a****@example.com
```

`detect(String)` 返回 `C2DataMatch` 集合。每个匹配项仅包含数据类型、起始位置和结束位置，不包含原始敏感值：

```java
for (C2DataMatch match : matches) {
    C2DataType type = match.type();
    int start = match.start();
    int end = match.end();
}
```

> [!IMPORTANT]
> `C2DataMatch` 不保存原始敏感数据，但调用方仍应避免记录传入 `detect` 或 `mask` 的原始文本。

### 默认识别器

默认 Builder 装配以下 Recognizer：

| Recognizer | 数据类型 | 识别规则 |
| --- | --- | --- |
| `PhoneNumberRecognizer` | `PHONE_NUMBER` | 使用 libphonenumber 查找并严格校验电话号码；无国家码号码按默认区域解释 |
| `MainlandChinaIdentityCardRecognizer` | `ID_CARD` | 识别 18 位中国大陆身份证号，并校验出生日期和 GB 11643 校验位 |
| `BankCardRecognizer` | `BANK_CARD` | 识别 12–19 位、允许空格或连字符分隔且通过 Luhn 校验的银行卡号 |
| `EmailPaymentAccountRecognizer` | `PAYMENT_ACCOUNT` | 将格式有效的邮箱识别为支付账号 |

默认电话区域为 `CN`。显式包含国际国家码的电话号码仍会按照其国家码解析。

电话区域必须是 libphonenumber 支持的区域代码；空值、空白值或未知代码会在构建时抛出 `IllegalArgumentException`，避免因配置错误而静默关闭本地号码识别。

### 默认脱敏规则

`C2DataMaskingService` 保留原文本长度。对于电话、身份证、银行卡和自定义类型，非字母数字分隔符保持不变；邮箱本地部分按照邮箱规则处理：

| 数据类型 | 默认规则 | 示例 |
| --- | --- | --- |
| 电话号码 | 保留国家码、号码前 3 位和后 4 位 | `+86 138 **** 8000` |
| 中国大陆身份证号 | 保留前 3 位和后 4 位 | `110***********1234` |
| 银行卡号 | 保留前 4 位和后 4 位，保留原分隔符 | `6222 **** **** 1234` |
| 邮箱支付账号 | 本地部分长度大于 1 时保留首字符，域名保持不变 | `a****@example.com` |
| 自定义类型 | 对匹配区间内的字母和数字全部打码，其他字符保持不变 | `***-********` |

邮箱本地部分只有一个字符时，该字符也会被打码。邮箱本地部分需要打码的范围会整体替换，因此该范围中的标点也会被替换。

### 配置掩码字符和电话区域

```java
C2DataMaskingService maskingService = C2DataMaskingService.builder()
        .maskCharacter('#')
        .defaultPhoneRegion("US")
        .build();
```

Builder 支持以下配置：

| 方法 | 默认值 | 说明 |
| --- | --- | --- |
| `maskCharacter(char)` | `*` | 设置所有数据类型使用的掩码字符 |
| `defaultPhoneRegion(String)` | `CN` | 设置无显式国家码电话号码的默认区域 |
| `addRecognizer(C2DataRecognizer)` | — | 在默认识别器集合的基础上追加 Recognizer |
| `recognizers(List<C2DataRecognizer>)` | 默认识别器集合 | 完全替换默认识别器以及此前追加的 Recognizer |

默认 `PhoneNumberRecognizer` 会在调用 `build()` 时根据 `defaultPhoneRegion` 创建，因此配置顺序不会影响电话区域。

如果使用 `recognizers(...)` 替换默认识别器，则 `defaultPhoneRegion` 不会修改调用方提供的 `PhoneNumberRecognizer`。此时应通过其构造方法显式指定区域。

### 组合识别器

使用 `addRecognizer` 可保留全部默认识别器，并追加自定义识别器：

```java
C2DataMaskingService maskingService = C2DataMaskingService.builder()
        .addRecognizer(customerAccountRecognizer)
        .build();
```

使用 `recognizers` 可完全替换默认集合。例如，以下配置只识别电话号码：

```java
C2DataMaskingService maskingService = C2DataMaskingService.builder()
        .recognizers(List.of(new PhoneNumberRecognizer("CN")))
        .build();
```

传入空集合可以关闭全部识别：

```java
C2DataMaskingService maskingService = C2DataMaskingService.builder()
        .recognizers(List.of())
        .build();
```

### 实现自定义 Recognizer

通过实现 `C2DataRecognizer` 可以扩展其他 C2 数据类型：

```java
public final class CustomerAccountRecognizer implements C2DataRecognizer {

    private static final Pattern PATTERN = Pattern.compile("ACC-[A-Z0-9]{8}");

    @Override
    public List<C2DataMatch> detect(String text) {
        List<C2DataMatch> matches = new ArrayList<>();
        Matcher matcher = PATTERN.matcher(text);
        while (matcher.find()) {
            matches.add(new C2DataMatch(
                    C2DataType.CUSTOM,
                    matcher.start(),
                    matcher.end()));
        }
        return matches;
    }

    @Override
    public int maxMatchLength() {
        return 12;
    }
}
```

如果识别结果依赖匹配区间之外的前后文，还应覆盖相应的上下文长度：

```java
@Override
public int maxLookbehindLength() {
    return 6;
}

@Override
public int maxLookaheadLength() {
    return 1;
}
```

两个上下文方法默认返回 `0`，不依赖区间外文本的 Recognizer 无需覆盖。

> [!IMPORTANT]
> `maxMatchLength()` 必须覆盖该 Recognizer 可能返回的最大匹配长度，`maxLookbehindLength()` 和 `maxLookaheadLength()` 必须覆盖可能影响识别的区间外上下文。流式脱敏使用这些值确定滚动缓冲及边界上下文；值过小可能导致跨 chunk 的结果与一次性脱敏不一致。

Service 会验证 Recognizer 返回的区间，并处理重叠匹配。内置数据类型的优先级从高到低依次为身份证、银行卡、支付账号、电话号码和自定义类型；同优先级下优先采用更完整的匹配区间。

### 直接处理流式文本

除 Advisor 外，也可以直接使用 `StreamingMaskingSession` 处理分段文本：

```java
C2DataMaskingService.StreamingMaskingSession session =
        maskingService.newStreamingSession();

String first = session.accept("联系电话 13800");
String second = session.accept("138000");
String remaining = session.finish();

String maskedText = first + second + remaining;
```

流式会话根据实际装配的 Recognizer 计算缓冲区和边界上下文长度。可能构成敏感数据的尾部文本会暂存在缓冲区中，已释放文本末尾所需的左侧上下文也会保留用于后续识别；`finish()` 会脱敏并刷新剩余内容。

> [!NOTE]
> 每条消息或每个响应订阅都应创建独立的 `StreamingMaskingSession`。会话不应跨请求共享，并且正常完成时必须调用 `finish()`。

## C2ToolArgumentsMaskingAdvisor

`C2ToolArgumentsMaskingAdvisor` 用于脱敏 Assistant Message 中的工具调用参数。它递归遍历参数 JSON 的对象和数组，仅处理字符串值。

例如，以下工具参数：

```json
{
  "customer": {
    "phone": "13800138000",
    "email": "alice@example.com"
  },
  "retryCount": 3
}
```

经过 Advisor 后会转换为：

```json
{
  "customer": {
    "phone": "138****8000",
    "email": "a****@example.com"
  },
  "retryCount": 3
}
```

对象键、数字、布尔值和 `null` 保持原类型和原值。JSON 被修改后会重新序列化，因此空格等非语义格式可能发生变化。

### 创建 Advisor

```java
C2ToolArgumentsMaskingAdvisor toolArgumentsAdvisor =
        C2ToolArgumentsMaskingAdvisor.builder(maskingService)
                .build();
```

可以显式覆盖执行顺序：

```java
C2ToolArgumentsMaskingAdvisor toolArgumentsAdvisor =
        C2ToolArgumentsMaskingAdvisor.builder(maskingService)
                .order(Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 100)
                .build();
```

通常无需修改默认顺序。默认值被设计为位于标准 `ToolCallAdvisor` 和默认 Chat Memory Advisor 的内侧，使工具执行和记忆持久化接收到的是脱敏后的参数。

### 非法 JSON

工具调用参数应当是合法 JSON。Advisor 对非法参数采取以下处理：

| 参数情况 | 行为 |
| --- | --- |
| JSON 合法 | 递归脱敏所有字符串值 |
| JSON 非法，但未检测到疑似 C2 数据 | 保持原参数，由后续组件按照原行为处理 |
| JSON 非法，且检测到疑似 C2 数据 | 抛出不包含原始参数的安全异常，并阻止工具执行 |

敏感非法 JSON 对应的异常消息为：

```text
Tool arguments contain C2 data but are not valid JSON
```

### 流式工具调用

在流式响应中，工具参数可能被拆分到多个 chunk。Advisor 会：

1. 按 tool-call ID 聚合同一个工具调用的参数片段；ID 缺失时使用稳定序号。
2. 暂存原始工具参数，不将包含明文参数的中间 tool call 传递给外层 Advisor。
3. 在工具调用完整后重建并发出脱敏的 tool call。
4. 在聚合工具参数期间继续传递普通 Assistant 文本 chunk。

该处理同时兼容增量参数片段和重复携带累计参数的流式模型响应。

## C2AssistantMessageMaskingAdvisor

`C2AssistantMessageMaskingAdvisor` 用于脱敏大语言模型返回的 Assistant Message 文本。

### 创建 Advisor

```java
C2AssistantMessageMaskingAdvisor assistantMessageAdvisor =
        C2AssistantMessageMaskingAdvisor.builder(maskingService)
                .build();
```

也可以显式设置顺序：

```java
C2AssistantMessageMaskingAdvisor assistantMessageAdvisor =
        C2AssistantMessageMaskingAdvisor.builder(maskingService)
                .order(Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 120)
                .build();
```

### 非流式响应

对于非流式响应，Advisor 会处理所有 Generation 中的 Assistant Message 文本，并保留：

- Tool Calls 和 Media
- Assistant Message 属性
- Generation 元数据
- ChatResponse 元数据
- Advisor Context

### 流式响应

流式响应中的敏感数据可能跨越多个 chunk。例如，手机号可能按以下方式返回：

```text
chunk 1: "联系电话 13800"
chunk 2: "138000"
```

Advisor 会为每个订阅以及每个稳定的模型 choice 创建隔离的滚动脱敏会话。存在 choice index 时优先使用该索引，缺失时才使用响应内序号兜底。第一段末尾可能构成敏感数据的内容不会立即以明文释放；收到后续 chunk 后，拼接结果与对完整文本调用 `mask(fullText)` 的结果一致。

流结束时，Advisor 会脱敏并刷新缓冲区中的剩余文本。错误和取消信号会正常传播，请求之间不共享流式状态。

> [!NOTE]
> 为避免跨 chunk 泄露，流式输出最多可能延迟当前 Recognizer 集合的最大匹配长度。若配置为空 Recognizer 集合，缓冲长度为 0，文本会直接释放。

## 与 ChatClient 集成

以下示例创建一个共享的 `C2DataMaskingService`，并将两个 Advisor 注册到 `ChatClient`：

```java
C2DataMaskingService maskingService = C2DataMaskingService.builder()
        .maskCharacter('*')
        .defaultPhoneRegion("CN")
        .build();

C2ToolArgumentsMaskingAdvisor toolArgumentsAdvisor =
        C2ToolArgumentsMaskingAdvisor.builder(maskingService)
                .build();

C2AssistantMessageMaskingAdvisor assistantMessageAdvisor =
        C2AssistantMessageMaskingAdvisor.builder(maskingService)
                .build();

ToolCallAdvisor toolCallAdvisor = ToolCallAdvisor.builder()
        .toolCallingManager(toolCallingManager)
        .build();

ChatClient chatClient = ChatClient.builder(chatModel)
        .defaultAdvisors(
                toolCallAdvisor,
                toolArgumentsAdvisor,
                assistantMessageAdvisor)
        .build();
```

随后可通过同一个 `ChatClient` 使用非流式或流式调用：

```java
String content = chatClient.prompt()
        .user("查询客户信息")
        .call()
        .content();
```

```java
Flux<String> content = chatClient.prompt()
        .user("查询客户信息")
        .stream()
        .content();
```

Advisor 链的执行顺序由 `getOrder()` 决定，而不是由注册顺序决定。数值较小的 Advisor 位于链的外层，数值较大的 Advisor 位于链的内层；响应按照相反方向返回。

> [!IMPORTANT]
> 两个 C2 Advisor 应接收同一个 `C2DataMaskingService` 实例。分别创建 Service 可能导致工具参数和 Assistant Message 使用不同的识别器、电话区域或掩码字符。

## Spring Bean 配置示例

应用也可以通过普通 Spring 配置类集中装配这些对象，无需组件扫描：

```java
@Configuration
public class C2MaskingConfiguration {

    @Bean
    C2DataMaskingService c2DataMaskingService() {
        return C2DataMaskingService.builder()
                .defaultPhoneRegion("CN")
                .build();
    }

    @Bean
    C2ToolArgumentsMaskingAdvisor c2ToolArgumentsMaskingAdvisor(
            C2DataMaskingService maskingService) {
        return C2ToolArgumentsMaskingAdvisor.builder(maskingService)
                .build();
    }

    @Bean
    C2AssistantMessageMaskingAdvisor c2AssistantMessageMaskingAdvisor(
            C2DataMaskingService maskingService) {
        return C2AssistantMessageMaskingAdvisor.builder(maskingService)
                .build();
    }
}
```

## 安全边界与限制

首版默认识别范围包括：

- 中国及国际电话号码
- 18 位中国大陆身份证号
- 通过 Luhn 校验的 12–19 位银行卡号
- 邮箱形式的支付账号

默认规则不识别以下数据：

- 15 位旧版身份证、护照、姓名和地址
- 普通用户名、商户号、订单号和业务 ID
- 无法仅凭文本结构可靠判断的任意支付账号

这些类型可通过自定义 `C2DataRecognizer` 扩展。

> [!WARNING]
> 脱敏用于减少敏感数据暴露，不等同于加密、访问控制或数据删除。应用仍需控制原始 Prompt、模型请求、日志、Tracing、异常信息和持久化数据的访问范围。C2 Advisor 只处理其所在 Advisor 链中经过的响应内容，不会自动清理在进入该链之前已经记录或发送的数据。

工具参数 Advisor 只脱敏 JSON 字符串值。即使数字节点看起来像手机号或银行卡号，也会保持原类型和原值；需要保护此类参数时，应优先将敏感标识定义为字符串类型，或在工具输入模型中增加专门处理。
