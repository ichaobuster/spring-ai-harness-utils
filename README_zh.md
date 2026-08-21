# Spring AI Harness Utils

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java Version](https://img.shields.io/badge/Java-17%2B-orange)](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)

[English](README.md)

一个基于 [Spring AI](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/index.html) 的工具库，为 AI 应用提供受 [Claude Code](https://code.claude.com/docs/en/settings#tools-available-to-claude) 启发的 harness 工具与 agent 技能管理能力。

## 概述

`spring-ai-harness-utils` 提供了一个统一的、安全的 **MCP (Model Context Protocol) Server**，用于替代 agent 内置的文件系统工具，实现工作区隔离、操作可审计以及版本回滚能力。通过禁用 agent 原生的文件操作并将其路由到本 MCP Server，实现 **一次开发，所有 agent 通用** 的安全文件访问方案。

除 MCP Server 外，本仓库还提供可复用的 Spring AI 组件：上下文压缩 Advisor、C2 敏感数据脱敏、基于 `StorageProvider` 的工具、classpath/工作区技能加载、HTTP MCP Tool Gateway，以及可执行的领域技能（例如电子表格工具）。

### 核心特性

- **🔒 工作区隔离**：每个 agent 在阿里云 OSS 上的 `{system}-{agent}-{user}` 前缀内严格隔离运行。绝对路径（以 `/` 开头）会被直接拒绝并抛出 `SecurityException`。
- **📁 完整文件工具集**：`Read`、`Write`、`Edit`、`Glob`、`Grep`、`ListDirectory`、`Trash` —— agent 所需的全部文件操作能力，通过 MCP 协议统一交付。
- **📸 快照与回滚**：自动前置操作快照，支持一键 `Rewind` 回滚。双重安全兜底机制确保可以撤销已执行的撤销操作。
- **🖼️ 多媒体读取**：图片（PNG/JPG/JPEG）自动按比例缩放后以 MCP `ImageContent` Base64 格式返回；PDF 支持页码范围提取；Office 文档（DOCX/XLSX/PPTX）解析为纯文本。
- **🔄 流式处理**：所有文件内容处理均通过 `FileContentProcessor` 以 `InputStream` 流式传输方式执行，有效防止处理大文件时的内存溢出（OOM）风险。
- **🎯 Agent 技能系统**：支持工作区级 `skills/` 目录的 `SKILL.md` 自动发现，同时通过 MCP Tools 和 `skill://` URI Resources 双协议暴露；也可通过 `SkillUtil` 加载 classpath 技能。
- **📊 文档技能（`spring-ai-skills`）**：基于 Apache POI 的 Spring AI `@Tool` 实现，支持通过 `StorageProvider` 处理 XLSX（创建/预览/编辑/公式/CSV）与 DOCX（创建/预览/替换/批注/接受修订）。
- **🚪 MCP Tool Gateway**：无状态网关，支持鉴权、基于 Header 的工具目录过滤，以及将 tool call 透明转发到下游 HTTP API。
- **📈 可插拔可观测性**：可选的 OpenTelemetry 链路追踪，采用零运行开销的装饰器模式 —— 关闭时无任何性能损耗。
- **🛡️ C2 数据脱敏**：识别并脱敏工具参数和 assistant message 中的手机号、中国大陆身份证、通过 Luhn 校验的银行卡号及邮箱形式支付账号，并支持跨 chunk 安全的流式输出。
- **🌐 Web 管理控制台**：基于 React 18 + Ant Design 5 构建，提供 Windows 资源管理器风格的文件管理、拖拽操作及 MCP Client JSON-RPC 调试器。

## 模块结构

| 模块 | 说明 |
|------|------|
| `spring-ai-harness-mcp-server` | 核心 MCP Server，包含文件工具、技能系统、快照回滚及流式多媒体支持 |
| `spring-ai-harness-server-frontend` | 基于 React 的 Web 管理控制台 |
| `spring-ai-harness-utils` | 存储抽象、Harness 工具、技能辅助、上下文压缩 Advisor 与 C2 数据脱敏 |
| `spring-ai-skills` | 以 Spring AI Tools 形式实现的可执行 Agent Skills（XLSX / DOCX） |
| `mcp-tool-gateway` | 无状态 MCP 网关：鉴权、权限过滤、HTTP Bypass 调用 |
| `spring-ai-harness-utils-bom` | BOM（Bill of Materials）版本统一管理 |

## 架构概览

```
Agents (openclaw / hermes / qwenpaw / ...)
    │  Authorization: {system}-{agent}-{user}
    │  POST /mcp (Stateless HTTP)
    ▼
spring-ai-harness-mcp-server
    ├── FileSystemTools (@McpTool) ──► StorageProvider (接口)
    │       Read / Write / Edit / ...       ├── AliyunOssStorage (实现)
    ├── SkillTools (@McpTool)               └── LocalFileStorage (实现)
    │       ListSkills / ReadSkill
    └── 快照 & 回滚                     FileContentProcessor
                                           (InputStream 流式内容处理)
                                                  │
                                            阿里云 OSS

可选配套 / 类库：
  spring-ai-skills   → XlsxTools / DocxTools（+ classpath skills/{xlsx,docx}/SKILL.md）
  mcp-tool-gateway   → Header 鉴权 + tools/list 过滤 + HTTP bypass tools/call
```

## 快速开始

### 前置条件

- Java 17+
- Maven 3.8+
- 阿里云 OSS Bucket（MCP Server 模块需要）

### 配置

```properties
# 阿里云 OSS 连接
aliyun.oss.endpoint=https://oss-cn-hangzhou.aliyuncs.com
aliyun.oss.access-key-id=<你的密钥ID>
aliyun.oss.access-key-secret=<你的密钥Secret>
spring.ai.harness.mcp.server.oss-bucket=<你的Bucket名称>

# 可选：可观测性配置
spring.ai.harness.mcp.server.observability.enabled=false
spring.ai.harness.mcp.server.observability.export-type=otlp
```

### 编译与运行

```bash
# 编译后端模块
./mvnw compile -pl spring-ai-harness-mcp-server
./mvnw compile -pl spring-ai-skills
./mvnw compile -pl mcp-tool-gateway

# 运行后端单元测试
./mvnw test -pl spring-ai-harness-mcp-server
./mvnw test -pl spring-ai-skills
./mvnw test -pl mcp-tool-gateway

# 启动前端开发服务器
cd spring-ai-harness-server-frontend
npm install
npm run dev
```

## C2 敏感数据脱敏

创建一个脱敏服务实例，并显式传给两个 Advisor。默认使用 `*` 打码：手机号保留国家码以及号码前3后4位，身份证保留前3后4位，银行卡保留前4后4位，邮箱保留域名。

```java
C2DataMaskingService maskingService = C2DataMaskingService.builder()
    .maskCharacter('*')
    .defaultPhoneRegion("CN")
    .build();

ChatClient chatClient = ChatClient.builder(chatModel)
    .defaultAdvisors(
        ToolCallAdvisor.builder()
            .toolCallingManager(toolCallingManager)
            .build(),
        C2ToolArgumentsMaskingAdvisor.builder(maskingService).build(),
        C2AssistantMessageMaskingAdvisor.builder(maskingService).build())
    .build();
```

`C2ToolArgumentsMaskingAdvisor` 在工具执行前递归脱敏 JSON 字符串节点，数值节点保持不变；如果 malformed JSON 中检测到 C2 数据，则以不包含原始参数的异常安全终止。`C2AssistantMessageMaskingAdvisor` 同时支持普通和流式调用，并通过有界尾部缓冲保证跨 chunk 的敏感值不会先以明文输出。

默认 Builder 会装配 `PhoneNumberRecognizer`、`MainlandChinaIdentityCardRecognizer`、`BankCardRecognizer` 和 `EmailPaymentAccountRecognizer`。使用 `addRecognizer(...)` 可在保留默认识别器的基础上追加自定义规则；使用 `recognizers(...)` 可完全替换默认集合，只启用指定识别器：

```java
C2DataMaskingService phoneOnly = C2DataMaskingService.builder()
    .recognizers(List.of(new PhoneNumberRecognizer("CN")))
    .build();

C2DataMaskingService extended = C2DataMaskingService.builder()
    .addRecognizer(customRecognizer)
    .build();
```

流式尾部缓冲和边界上下文长度会根据实际装配的识别器计算；传入 `recognizers(List.of())` 会关闭识别并使用零长度缓冲。

有关识别规则、自定义 Recognizer、流式行为和 Advisor 顺序的完整说明，请参阅 [C2 数据识别与脱敏](docs/c2-data-masking.md)。

## 使用 `spring-ai-skills`（XLSX / DOCX）

通过 BOM 引入依赖：

```xml
<dependency>
  <groupId>io.github.spring-ai.harness</groupId>
  <artifactId>spring-ai-skills</artifactId>
</dependency>
```

注册工具，并可选加载内置技能提示词：

```java
StorageProvider storage = LocalFileStorage.builder()
    .baseDir(Path.of("/path/to/workspace"))
    .build();

XlsxTools xlsxTools = new XlsxTools(); // local filesystem paths

OssLocalFileTools bridge = OssLocalFileTools.builder()
    .ossClient(ossClient)
    .bucketName("your-bucket")
    .prefix("mcp/workspaces/sys-agent-user/") // optional
    .downloadPath(Path.of("/tmp"))            // optional
    .build();
// bridge.downloadOssFileToLocal("reports/a.xlsx") -> /tmp/{uuid}/a.xlsx

// 作为 Spring AI tools 挂到 ChatClient / ToolCallback 提供者
// ToolCallbacks.from(xlsxTools)

DocxTools docxTools = new DocxTools();
// ToolCallbacks.from(docxTools)
// Local filesystem paths; use OssLocalFileTools for remote files
DocxTools docxTools = new DocxTools();
// ToolCallbacks.from(docxTools)
// Local filesystem paths; use OssLocalFileTools for remote files
// 加载 classpath SKILL.md，供 SkillsTool / 系统提示注入
List<SkillsTool.Skill> skills = SkillUtil.loadClassPath("classpath*:skills/**/SKILL.md");
```

### XLSX 工具一览

| 工具 | 说明 |
|------|------|
| `readXlsxPreview` | Markdown 预览：工作表名、维度与前若干行 |
| `readXlsxSheet` | 分页读取单元格值或原始公式 |
| `createXlsx` | 根据 `SheetSpec` / `CellSpec` JSON 创建工作簿 |
| `editXlsxCells` | 定点编辑单元格，不破坏未涉及内容 |
| `evaluateXlsxFormulas` | 使用 POI 重算公式并汇总错误 |
| `convertCsvToXlsx` | 以 SXSSF 流式将 CSV/TSV 转为 `.xlsx` |

### DOCX 工具面

| 工具 | 说明 |
|------|------|
| `readDocxPreview` | Markdown 概览：段落/表格数量与前若干正文块 |
| `readDocxContent` | 分页读取正文（段落 + Markdown 表格） |
| `createDocx` | 按块规范创建 `.docx`（paragraph/table/pageBreak） |
| `replaceDocxText` | 正文与表格内查找替换 |
| `mergeDocxRuns` | 合并相邻同格式 run，便于查找 |
| `addDocxComment` | 添加批注（可选锚点文本） |
| `acceptDocxTrackedChanges` | 接受插入修订并删除删除修订 |

XLSX/DOCX 工具路径为本地文件系统路径（绝对或相对）。远端存储文件请先通过 `OssLocalFileTools` 落盘。

## MCP 工具参考

| 工具 | 说明 |
|------|------|
| `Read` | 读取文件并带行号输出。图片返回 Base64 编码的 `ImageContent`；PDF/Office 文档返回提取的文本内容。PDF 支持 `startPage`/`endPage` 页码范围。 |
| `Write` | 创建或覆写文件（自动生成前置快照） |
| `Edit` | 精确字符串替换，支持单次/全部替换模式（自动生成前置快照） |
| `Glob` | Glob 模式文件搜索（最多返回 100 个结果） |
| `Grep` | 正则表达式搜索，支持上下文行与分页 |
| `ListDirectory` | 列出文件和子目录及元数据信息 |
| `Trash` | 安全软删除至 `.trash/`（自动生成前置快照） |
| `ListSnapshots` | 查询快照历史，支持按路径过滤 |
| `Rewind` | 恢复文件到指定快照状态 |
| `ListSkills` | 发现工作区内可用的技能 |
| `ReadSkill` | 读取技能指令内容 |

## 许可证

本项目采用 [Apache License 2.0](LICENSE.txt) 许可证。
