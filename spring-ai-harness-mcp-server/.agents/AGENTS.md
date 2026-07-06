# Spring AI Harness MCP Server — AGENTS.md

> [!IMPORTANT]
> **本项目仍处于积极开发阶段**，存在大量尚未开发完成、需要打磨改进以及需要补充新增的功能。
> 本文档（AGENTS.md）应随项目的演进**持续更新**，确保始终反映代码的最新状态。
> 若你在开发过程中发现本文档与代码实际情况不一致，请在完成代码修改后**同步更新本文档**。

## 项目概述

`spring-ai-harness-mcp-server` 是一个统一的 **MCP (Model Context Protocol) Server**，为不同的 harness agents（如 openclaw、hermes、qwenpaw 等）提供安全可控的文件系统操作能力。

### 为什么需要这个项目

不同的 agents 使用不同的开发语言、SDK、plugin 和 hook，导致为解决 agent loop runtime 越权读写文件、执行高危命令等安全问题，需要在各个 agent 上分别开发多种 plugins。而所有这些 agents 都支持 MCP Server 协议，因此通过：

1. **禁用** agents 内置的文件操作工具（read、write、edit、glob、grep 等）
2. **替代** 为统一的 MCP Server 提供这些能力

即可**一次开发，解决所有 agents 上的文件读写越权问题**。

### 为什么文件系统与沙箱分离

沙箱（sandbox）需要按用户隔离容器，用户量大时需要大量计算资源；而 filesystem MCP Server 无需隔离容器，少量计算资源即可服务大量用户。我们假设 filesystem 操作远多于沙箱操作，因此分离二者可以显著节省计算资源。

---

## 架构设计

### 核心架构图

```
┌──────────────────────────────────────────────────────────────────┐
│                        MCP Clients (Agents)                      │
│         openclaw / hermes / qwenpaw / ...                        │
│         Authorization: {system}-{agent}-{user}                   │
└─────────────────────────┬────────────────────────────────────────┘
                          │ HTTP (Streamable HTTP, Stateless)
                          │ POST /mcp
                          ▼
┌──────────────────────────────────────────────────────────────────┐
│              spring-ai-harness-mcp-server                        │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐     │
│  │  HarnessMcpServerAutoConfiguration                      │     │
│  │  • WebMvcStatelessServerTransport (Stateless MCP)       │     │
│  │  • contextExtractor → ServerRequest → McpTransportCtx   │     │
│  └─────────────────────────────────────────────────────────┘     │
│                          │                                       │
│                          ▼                                       │
│  ┌─────────────────────────────────────────────────────────┐     │
│  │  FileSystemTools (@McpTool)                             │     │
│  │  • Read / Write / Edit / Glob / Grep                    │     │
│  │  • 从 Authorization 头解析 system-agent-user            │     │
│  │  • 每次请求动态创建 StorageProvider（含 workspace 隔离） │     │
│  └──────────────────────┬──────────────────────────────────┘     │
│                         │                                        │
│                         ▼                                        │
│  ┌─────────────────────────────────────────────────────────┐     │
│  │  StorageProvider (接口)                                  │     │
│  │  └─ AliyunOssStorage (实现)                              │     │
│  │     • bucket: ${spring.ai.harness.mcp.server.oss-bucket}│     │
│  │     • prefix: mcp/workspaces/{system}/{agent}/{user}/   │     │
│  │     • 所有路径操作均限制在 prefix 下                      │     │
│  └──────────────────────┬──────────────────────────────────┘     │
│                         │                                        │
└─────────────────────────┼────────────────────────────────────────┘
                          │ Aliyun OSS SDK
                          ▼
               ┌─────────────────────┐
               │   Alibaba Cloud OSS │
               │   (文件存储介质)      │
               └─────────────────────┘
```

### Workspace 隔离模型

```
oss://{bucket}/{ossPrefix}/{system}/{agent}/{user}/
       │         │           │        │       │
       │         │           │        │       └── 用户维度隔离
       │         │           │        └────────── Agent 维度隔离
       │         │           └─────────────────── 系统维度隔离
       │         └─────────────────────────────── 可配置前缀 (默认 mcp/workspaces/)
       └───────────────────────────────────────── OSS Bucket
```

对于 agent 来说：
- **pwd 是 `./`**，所有路径操作以此为基准
- **绝对路径被禁止**：以 `/` 开头的路径会抛出 `SecurityException`
- **`..` 路径逃逸**：TODO - 当前需加强对 `../` 的防护
- Agent 无法感知自身在 OSS 中的真实位置

### 沙箱集成（规划中）

当 agent 需要执行 shell 或 browser 操作时：
1. 将 workspace 的 OSS 路径 mount 到 all-in-one sandbox 的 `/workspace` 目录
2. 沙箱内的操作可访问 workspace 下的文件
3. 沙箱按用户隔离容器

### 文件防护能力（规划中）

- 利用 OSS 版本控制能力实现文件操作回滚
- 或自行编写 workspace 的备份/快照方案
- 所有通过 MCP Server 的文件操作可被撤回到有限的时间点之前
- 防止 agent 的误操作或 shell 执行脚本的破坏性操作

---

## 模块结构

```
spring-ai-harness-mcp-server/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/io/github/springai/harness/
    │   │   ├── autoconfig/
    │   │   │   ├── AliyunOssAutoConfiguration.java   # OSS 客户端自动配置
    │   │   │   ├── AliyunOssProperties.java          # OSS 连接配置属性
    │   │   │   ├── HarnessMcpServerAutoConfiguration.java  # MCP Server 自动配置
    │   │   │   └── HarnessMcpServerProperties.java   # MCP Server 配置属性
    │   │   ├── storage/
    │   │   │   ├── StorageProvider.java               # 存储抽象接口
    │   │   │   └── AliyunOssStorage.java              # 阿里云 OSS 实现
    │   │   └── tool/
    │   │       └── FileSystemTools.java               # MCP 工具定义（Read/Write/Edit/Glob/Grep）
    │   └── resources/
    │       └── application.properties                 # 默认配置
    └── test/
        └── java/io/github/springai/harness/
            ├── storage/
            │   └── AliyunOssStorageTest.java
            └── tool/
                └── FileSystemToolsTest.java
```

---

## 核心组件详解

### 1. FileSystemTools

文件：`tool/FileSystemTools.java`

MCP 工具入口类，提供 7 个 `@McpTool` 方法，是 agent 可调用的全部文件系统能力：

| 工具名 | 方法 | 功能 |
|--------|------|------|
| `Read` | `read(ctx, filePath, offset, limit)` | 读取文件内容，支持分页，输出带行号（`cat -n` 格式）|
| `Write` | `write(ctx, filePath, content)` | 创建或覆写文件 |
| `Edit` | `edit(ctx, filePath, oldString, newString, replaceAll)` | 精确字符串替换，支持单次/全部替换 |
| `Glob` | `glob(ctx, pattern, path)` | Glob 模式文件搜索，返回最多 100 个结果 |
| `Grep` | `grep(ctx, pattern, path, glob, outputMode, ...)` | 正则搜索，支持上下文行、行号、分页等 |
| `ListDirectory` | `listDirectory(ctx, path)` | 列出指定目录下的文件和子目录列表（含类型、大小、修改时间） |
| `Trash` | `trash(ctx, filePath)` | 安全地将文件或目录移动到工作区回收站（`.trash/`） |

**关键安全逻辑**：`getStorageProvider(McpTransportContext)` 方法从每个请求的 `Authorization` 头中解析出 `{system}-{agent}-{user}` 三元组，动态构建隔离的 `AliyunOssStorage` 实例。

### 2. StorageProvider

文件：`storage/StorageProvider.java`

存储抽象接口，定义了所有文件操作契约。关键常量：

| 常量 | 值 | 说明 |
|------|----|------|
| `MAX_RESULT` | 100 | Glob 最大返回结果数 |
| `MAX_DEPTH` | 50 | 最大遍历深度 |
| `MAX_LINES` | 2000 | Read 默认最大读取行数 |
| `MAX_LINE_LENGTH` | 2000 | 单行最大长度（超出截断）|
| `DEFAULT_HEAD_LIMIT` | 250 | Grep 默认 head 限制 |
| `IGNORED_PATH_PATTERN` | `.git`, `node_modules` 等 | 自动忽略的路径模式 |

### 3. AliyunOssStorage

文件：`storage/AliyunOssStorage.java`

`StorageProvider` 的阿里云 OSS 实现。**安全核心**在 `getFullKey(path)` 方法：

```java
private String getFullKey(String path) {
    if (path.startsWith("/")) {
        throw new SecurityException("Absolute paths are not allowed: '" + path + "'");
    }
    if (path.startsWith("./")) {
        return this.prefix + path.substring(2);
    }
    return this.prefix + path;
}
```

### 4. AutoConfiguration

- **`AliyunOssAutoConfiguration`**：根据 `aliyun.oss.*` 配置创建 `OSS` 客户端 Bean
- **`HarnessMcpServerAutoConfiguration`**：配置 Stateless MCP Server 传输层，通过 `contextExtractor` 将 `ServerRequest` 注入 `McpTransportContext`

---

## 配置参考

### 必需配置

```properties
# 阿里云 OSS 连接
aliyun.oss.endpoint=https://oss-cn-xxx.aliyuncs.com
aliyun.oss.access-key-id=<your-access-key-id>
aliyun.oss.access-key-secret=<your-access-key-secret>

# MCP Server
spring.ai.harness.mcp.server.oss-bucket=<your-bucket-name>
```

### 可选配置

```properties
# Workspace 路径前缀 (默认值: mcp/workspaces/)
spring.ai.harness.mcp.server.oss-prefix=mcp/workspaces/

# MCP 端点 (默认值: /mcp)
spring.ai.mcp.server.stateless.mcp-endpoint=/mcp
```

---

## 安全规范

### 必须遵守

- **绝不允许**引入绕过 workspace 隔离的代码路径
- **所有文件路径操作**必须经过 `getFullKey()` 方法，确保路径被限制在 workspace prefix 下
- **绝对路径**（以 `/` 开头）必须被拒绝并抛出 `SecurityException`
- **Authorization 头**是唯一的身份识别来源，格式为 `{system}-{agent}-{user}`，必须严格校验
- **路径解析与 OSS 机制**：在 OSS 原生 SDK 中，Object Key 为字面量字符串，不会像 POSIX 文件系统一样将 `..` 解析为父级目录。`getFullKey()` 给每个 key 强制拼接 `prefix` 后，包含 `..` 的 key 依然位于 `prefix` 下，因此通过 OSS SDK 不会导致跨 workspace 越权逃逸。但在进行路径规范化处理（例如挂载到 POSIX 沙箱）时仍建议去除 `..` 变体
- **不得暴露** OSS bucket、prefix 等内部存储细节给 agent
- **工具方法的错误信息**中不得包含完整的 OSS key 路径，只返回相对路径

### 安全 TODO

> ⚠️ 以下是已知需要强化的安全点，开发时应优先考虑：

1. Authorization 头的解析逻辑需要加强（当前仅做简单 `-` 分割，待重构为独立认证模块）
2. 路径规范化（normalize）处理，清洗 `./foo/../bar` 等不规范路径
3. 需要考虑沙箱挂载后的 POSIX 符号链接/路径逃逸风险

---

## 开发规范

### 技术栈

- **Java 17+**
- **Spring Boot 3.5.x**
- **Spring AI 1.1.x**（MCP Server 实现）
- **Aliyun OSS SDK 3.18.x**
- **Lombok**（`@Data`, `@Slf4j` 等）
- **MCP 注解**：`@McpTool`、`@McpToolParam`（来自 `org.springaicommunity.mcp.annotation`）

### 编码约定

- 使用中文注释，英文代码
- `@McpTool` 的 `description` 使用英文，因为这是提供给 LLM 阅读的 prompt
- 使用 Lombok 减少样板代码
- 配置属性类使用 `@ConfigurationProperties`
- 自动配置类使用 `@ConditionalOnMissingBean` 保证可扩展性
- 遵循 Spring 的 `// @formatter:off` / `// @formatter:on` 注释控制格式化
- **常量值修改需同步 prompt**：`StorageProvider` 中标注了「如有变动，需同步修改 prompt」的常量（如 `MAX_LINES`、`MAX_LINE_LENGTH`）与 `@McpTool` 的 description 文本耦合，修改时务必两侧同步

### 分层职责

| 层 | 职责 | 安全边界 |
|----|------|----------|
| `tool/` | MCP 工具定义、参数校验、身份识别 | Authorization 解析、workspace 路由 |
| `storage/` | 存储抽象与实现、文件操作 | 路径隔离、绝对路径拦截 |
| `autoconfig/` | Spring Boot 自动配置、Bean 装配 | 配置注入 |

### 新增 MCP 工具的流程

1. 在 `FileSystemTools` 中添加 `@McpTool` 方法
2. 方法第一个参数必须是 `McpTransportContext context`
3. 调用 `getStorageProvider(context)` 获取隔离的 `StorageProvider`
4. 在 `StorageProvider` 接口中定义新操作
5. 在 `AliyunOssStorage` 中实现
6. 编写单元测试（mock OSS 客户端）
7. 确保所有路径操作通过 `getFullKey()` 进行安全校验

### 新增 StorageProvider 实现的流程

1. 实现 `StorageProvider` 接口
2. 确保 `getFullKey()` 或等价方法拒绝绝对路径和 `..` 遍历
3. 编写对应的 `AutoConfiguration` 和 `Properties` 类
4. 添加 `@ConditionalOnMissingBean` 或 `@ConditionalOnProperty` 保证与现有实现不冲突

---

## 测试

### 构建与测试命令

```bash
# 仅编译本模块
./mvnw compile -pl spring-ai-harness-mcp-server

# 运行本模块单元测试
./mvnw test -pl spring-ai-harness-mcp-server

# 运行全量测试
./mvnw test
```

### 测试约定

- 使用 **JUnit 5** + **Mockito** + **AssertJ**
- OSS 客户端必须 mock，不得在单元测试中连接真实 OSS
- 安全相关测试用例（路径逃逸、绝对路径拒绝等）必须覆盖
- 测试类命名：`{被测类名}Test.java`

---

## 相关模块

- [`spring-ai-harness-utils`](../spring-ai-harness-utils/)：提供 Context Compact（上下文压缩）等 advisor 能力，用于 agent loop 的上下文管理
- [`spring-ai-harness-utils-bom`](../spring-ai-harness-utils-bom/)：BOM（Bill of Materials）版本管理
- [`examples/`](../examples/)：示例项目

---

## 文档维护

> [!CAUTION]
> **本文档是活文档（Living Document）**，必须随项目代码的变更同步维护。过时的文档比没有文档更具误导性。

### 何时更新本文档

以下情况发生时，**必须**同步更新本 AGENTS.md：

- **新增/删除/重命名** MCP 工具方法（`@McpTool`）
- **新增/删除/重命名** 源文件、包或模块结构变化
- **修改** `StorageProvider` 接口契约（新增方法、修改常量等）
- **新增** `StorageProvider` 实现（如从 OSS 扩展到其他存储）
- **修改** 安全相关逻辑（路径校验、Authorization 解析、workspace 隔离）
- **修改** 配置属性（新增/删除/改名 `@ConfigurationProperties` 字段）
- **完成** 规划中的功能（沙箱集成、文件版本控制/快照等）
- **修复** 安全 TODO 项
- **变更** 技术栈版本（Spring Boot、Spring AI、OSS SDK 等重大升级）

### 如何更新

1. 定位到本文档中受影响的章节
2. 更新描述、表格、代码示例或架构图，使其与代码一致
3. 若功能从「规划中」变为「已实现」，将其从规划章节移至对应的正式章节
4. 若新增了尚未完成的 TODO，添加到「安全 TODO」或对应章节
5. 确保模块结构树（目录结构）与实际文件一致
