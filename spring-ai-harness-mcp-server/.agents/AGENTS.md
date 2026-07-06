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
│  │  • Register AuthenticationProvider & StorageFactory     │     │
│  └─────────────────────────────────────────────────────────┘     │
│                          │                                       │
│                          ▼                                       │
│  ┌─────────────────────────────────────────────────────────┐     │
│  │  Authentication & Storage Factory                       │     │
│  │  • HeaderAuthenticationProvider → WorkspaceIdentity     │     │
│  │  • DefaultStorageProviderFactory → AliyunOssStorage     │     │
│  └──────────────────────┬──────────────────────────────────┘     │
│                         │                                        │
│                         ▼                                        │
│  ┌─────────────────────────────────────────────────────────┐     │
│  │  FileSystemTools (@McpTool)                             │     │
│  │  • Read / Write / Edit / Glob / Grep / ListDirectory / Trash  │
│  │  • 委托 StorageProviderFactory 动态获取隔离 Storage      │     │
│  └──────────────────────┬──────────────────────────────────┘     │
│                         │                                        │
│                         ▼                                        │
│  ┌─────────────────────────────────────────────────────────┐     │
│  │  StorageProvider (接口)                                  │     │
│  │  └─ AliyunOssStorage (实现)                              │     │
│  │     • bucket: ${spring.ai.harness.mcp.server.oss-bucket}│     │
│  │     • prefix: mcp/workspaces/{system}-{agent}-{user}/   │     │
│  │     • 所有路径操作及回收站操作均限制在 prefix 下           │     │
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
oss://{bucket}/{ossPrefix}/{system}-{agent}-{user}/
       │         │           │        │       │
       │         │           │        │       └── 用户维度隔离 (分隔符 -)
       │         │           │        └────────── Agent 维度隔离 (分隔符 -)
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
    │   │   ├── auth/
    │   │   │   ├── AuthenticationException.java       # 认证异常类
    │   │   │   ├── AuthenticationProvider.java        # 认证抽象接口
    │   │   │   ├── HeaderAuthenticationProvider.java  # 请求头认证实现类
    │   │   │   └── WorkspaceIdentity.java             # 工作区身份信息 record
    │   │   ├── autoconfig/
    │   │   │   ├── AliyunOssAutoConfiguration.java   # OSS 客户端自动配置
    │   │   │   ├── AliyunOssProperties.java          # OSS 连接配置属性
    │   │   │   ├── HarnessMcpServerAutoConfiguration.java  # MCP Server 自动配置
    │   │   │   └── HarnessMcpServerProperties.java   # MCP Server 配置属性
    │   │   ├── controller/
    │   │   │   ├── WorkspaceApiController.java        # 用户工作区文件与快照管理 REST Controller (/api/v1/workspace)
    │   │   │   └── AdminApiController.java            # 管理员工作区与文件运维 REST Controller (/api/v1/admin)
    │   │   ├── dto/
    │   │   │   ├── FileItemDto.java                   # 文件列表项 DTO
    │   │   │   └── WorkspaceInfoDto.java              # 工作区元数据 DTO
    │   │   ├── skill/
    │   │   │   ├── MarkdownParser.java                # Markdown 与 YAML FrontMatter 解析器
    │   │   │   ├── SkillInfo.java                     # Skill 描述元数据 Record (basePath, frontMatter, content)
    │   │   │   ├── SkillProvider.java                 # Skill 发现与读取接口
    │   │   │   └── DefaultSkillProvider.java          # Workspace Skill 扫描与读取实现类
    │   │   ├── snapshot/
    │   │   │   ├── SnapshotInfo.java                  # 快照描述元数据 Record (snapshotId, filePath, action, timestamp)
    │   │   │   ├── SnapshotProvider.java              # 快照创建、列表与恢复接口
    │   │   │   └── DefaultSnapshotProvider.java       # 基于 .snapshots/ 的快照提供者实现
    │   │   ├── storage/
    │   │   │   ├── StorageProvider.java               # 存储抽象接口
    │   │   │   ├── StorageProviderFactory.java        # 存储工厂抽象接口
    │   │   │   ├── DefaultStorageProviderFactory.java # 默认存储工厂实现
    │   │   │   └── AliyunOssStorage.java              # 阿里云 OSS 实现
    │   │   └── tool/
    │   │       ├── FileSystemTools.java               # MCP 文件工具定义（Read/Write/Edit/Glob/Grep/ListDirectory/Trash/ListSnapshots/Rewind）
    │   │       └── SkillTools.java                    # MCP Skill 工具与 Resource 定义（ListSkills/ReadSkill/skill://URI）
    │   └── resources/
    │       └── application.properties                 # 默认配置
    └── test/
        └── java/io/github/springai/harness/
            ├── auth/
            │   └── HeaderAuthenticationProviderTest.java
            ├── controller/
            │   ├── WorkspaceApiControllerTest.java
            │   └── AdminApiControllerTest.java
            ├── skill/
            │   └── DefaultSkillProviderTest.java
            ├── snapshot/
            │   └── DefaultSnapshotProviderTest.java
            ├── storage/
            │   └── AliyunOssStorageTest.java
            └── tool/
                ├── FileSystemToolsTest.java
                └── SkillToolsTest.java
```

---

## 核心组件详解

### 1. FileSystemTools

文件：`tool/FileSystemTools.java`

MCP 工具入口类，提供 9 个 `@McpTool` 方法，是 agent 可调用的文件系统与快照回滚能力：

| 工具名 | 方法 | 功能 |
|--------|------|------|
| `Read` | `read(ctx, filePath, offset, limit)` | 读取文件内容，支持分页，输出带行号（`cat -n` 格式）|
| `Write` | `write(ctx, filePath, content)` | 创建或覆写文件（自动触发操作前快照） |
| `Edit` | `edit(ctx, filePath, oldString, newString, replaceAll)` | 精确字符串替换，支持单次/全部替换（自动触发操作前快照） |
| `Glob` | `glob(ctx, pattern, path)` | Glob 模式文件搜索，返回最多 100 个结果 |
| `Grep` | `grep(ctx, pattern, path, glob, outputMode, ...)` | 正则搜索，支持上下文行、行号、分页等 |
| `ListDirectory` | `listDirectory(ctx, path)` | 列出指定目录下的文件和子目录列表（含类型、大小、修改时间） |
| `Trash` | `trash(ctx, filePath)` | 安全地将文件或目录移动到工作区回收站（`.trash/`，自动触发操作前快照） |
| `ListSnapshots` | `listSnapshots(ctx, filePath)` | 查询历史快照列表，可按文件路径过滤 |
| `Rewind` | `rewind(ctx, snapshotId)` | 快速撤回/恢复文件到指定快照状态 |

**关键解耦设计**：`FileSystemTools` 不再感知 Authorization Header 解析逻辑或 OSS Client，而是统一注入 `StorageProviderFactory`。`getStorageProvider(McpTransportContext)` 方法委托给 `StorageProviderFactory` 获取为当前请求身份定制的 `StorageProvider` 实例。

### 2. SkillTools (Skill MCP Tools & Resources)

文件：`tool/SkillTools.java`

MCP Skill 管理入口类，将 Skill 能力独立抽取，同时暴露 **MCP Tools** 与 **MCP Resources** 两种协议范式：

#### MCP Tools

| 工具名 | 方法 | 功能 |
|--------|------|------|
| `ListSkills` | `listSkills(ctx)` | 列出工作区 `skills/` 目录下可用的全部 Skills，含名称、基目录与描述 |
| `ReadSkill` | `readSkill(ctx, skillName)` | 读取指定 Skill 的完整 `SKILL.md` 指令内容（附带基目录 Header） |

#### MCP Resources

| Resource URI | 方法 | MimeType | 功能 |
|--------------|------|----------|------|
| `skill://list` | `listSkillsResource(ctx)` | `text/plain` | 通过 MCP Resource 协议直接拉取全量 Skills 元数据列表 |
| `skill://{skillName}` | `readSkillResource(ctx, skillName)` | `text/markdown` | 通过 `skill://{skillName}` URI 协议标准读取指定 Skill 内容 |

### 4. Management REST API Module (管理功能 REST API)

包路径：`controller/`, `dto/`

#### 用户工作区端点 (`WorkspaceApiController` - `/api/v1/workspace`)

需要通过请求头 `Authorization: {system}-{agent}-{user}` 传递身份。

| HTTP 方法 | 请求路径 | 功能 | 说明 |
|-----------|---------|------|------|
| `GET` | `/api/v1/workspace/files?path=` | 列出工作区目录文件 | 返回 `List<FileItemDto>` |
| `GET` | `/api/v1/workspace/files/content?path=` | 读取/下载文件内容 | 返回文本内容 |
| `POST` | `/api/v1/workspace/files/upload?path=` | 上传/写入文件 | Body 传文本，触发自动前置快照 |
| `POST` | `/api/v1/workspace/files/move?fromPath=&toPath=` | 移动/重命名文件或目录 | 触发自动前置快照 |
| `DELETE` | `/api/v1/workspace/files?path=&trash=true` | 删除或移入回收站 | 默认移入回收站，触发自动前置快照 |
| `GET` | `/api/v1/workspace/snapshots?path=` | 查询文件历史快照列表 | 返回 `List<SnapshotInfo>` |
| `POST` | `/api/v1/workspace/rewind/{snapshotId}` | 一键回滚文件到特定快照 | 自动触发安全兜底快照 |

#### 管理员端点 (`AdminApiController` - `/api/v1/admin`)

需要通过请求头 `X-Admin-Token: {adminToken}` 验证管理员身份（配置项 `spring.ai.harness.mcp.server.admin-token`）。

| HTTP 方法 | 请求路径 | 功能 | 说明 |
|-----------|---------|------|------|
| `GET` | `/api/v1/admin/workspaces` | 列出 OSS 所有工作区空间列表 | 返回 `List<WorkspaceInfoDto>` |
| `GET` | `/api/v1/admin/workspaces/{workspaceKey}/files?path=` | 管理员列出特定工作区文件 | 返回 `List<FileItemDto>` |
| `POST` | `/api/v1/admin/workspaces/{workspaceKey}/files/move?fromPath=&toPath=` | 管理员移动/重命名特定工作区文件或目录 | 重命名/移动指定路径 |
| `DELETE` | `/api/v1/admin/workspaces/{workspaceKey}/files?path=` | 管理员强制删除特定工作区文件 | 清理指定文件 |

包路径：`snapshot/`

- **`SnapshotInfo`**：快照元数据 Record（`snapshotId`, `filePath`, `action`, `snapshotPath`, `timestamp`）。
- **`SnapshotProvider`**：快照服务抽象接口，定义 `createSnapshot`, `listSnapshots`, `rewind` 契约。
- **`DefaultSnapshotProvider`**：基于 `.snapshots/{snapshotId}/` 路径的机制实现。
  - **自动前置快照**：在 `Write`（修改既有文件）、`Edit`、`Trash` 真正执行修改前自动生成快照。
  - **元数据存储**：在 `.snapshots/{snapshotId}/meta.txt` 中记录 `filePath`、`action` 及毫秒时间戳。
  - **安全恢复与双重兜底**：调用 `rewind` 恢复旧快照时，系统会在覆盖当前文件前自动再生成一个 `action=REWIND` 的安全快照，实现随时撤回与再撤回。
  - **路径隐藏**：`.snapshots/` 被硬编码计入 `StorageProvider.IGNORED_PATH_PATTERN`，避免污染普通目录列表。

包路径：`skill/`

- **`MarkdownParser`**：Markdown 与 YAML Frontmatter 解析器，提取 `---` 包裹的元数据到 `Map<String, Object>`，并分离 Markdown Body 内容。
- **`SkillInfo`**：Skill 数据结构 Record（`basePath`, `frontMatter`, `content`），提供 `name()` 与 `description()` 动态提取方法。
- **`SkillProvider`**：Skills 发现与读取抽象接口，定义 `listSkills(McpTransportContext)` 与 `readSkill(McpTransportContext, String)` 契约。
- **`DefaultSkillProvider`**：
  - 使用 `storageProvider.glob("**/SKILL.md", "skills")` 自动扫描当前工作区 `skills/` 目录下的所有 `SKILL.md`。
  - `readSkill` 返回格式如 `Base directory for this skill: skills/{skillName}\n\n{content}`，指导 Agent 在自身工作区内访问该 Skill 的 `scripts/` 或 `references/` 资源。
- **关于公共/全局 Skills 的架构考虑**：全局 Skills 存储于 Agent 工作区 prefix 之外。当 Skill 内部包含关联文档 (`references/`) 或脚本 (`scripts/`) 时，由于沙箱和 MCP 读写限制在工作区根路径，跨区读取及 OSS 软链接机制受限。因此**暂不引入跨 workspace 的全局 Skills 共享**，确保工作区安全隔离边界。

### 3. Authentication Module (认证模块)

包路径：`auth/`

- **`WorkspaceIdentity`**：身份信息 Record (`system`, `agent`, `user`)，提供 `getWorkspacePath(prefix)` 方法生成格式化的 OSS 前缀路径（如 `mcp/workspaces/sys1-agent2-user3/`）。
- **`AuthenticationException`**：认证与 Authorization 解析异常类。
- **`AuthenticationProvider`**：认证抽象接口，定义 `authenticate(ServerRequest)` 契约。
- **`HeaderAuthenticationProvider`**：从 `Authorization` Header 解析身份，支持 `system-agent-user` 及 `system/agent/user` 格式（自动兼容 `Bearer` 前缀与首尾空白清洗）。

### 3. StorageProvider & StorageProviderFactory

包路径：`storage/`

存储抽象接口，定义了所有文件与回收站操作契约。关键常量：

| 常量 | 值 | 说明 |
|------|----|------|
| `MAX_RESULT` | 100 | Glob 最大返回结果数 |
| `MAX_DEPTH` | 50 | 最大遍历深度 |
| `MAX_LINES` | 2000 | Read 默认最大读取行数 |
| `MAX_LINE_LENGTH` | 2000 | 单行最大长度（超出截断）|
| `DEFAULT_HEAD_LIMIT` | 250 | Grep 默认 head 限制 |
| `IGNORED_PATH_PATTERN` | `.git`, `node_modules`, `.trash`, `.snapshots` 等 | 自动忽略的路径模式 |

工厂与实现：
- **`StorageProviderFactory`**：定义 `getStorageProvider(McpTransportContext)` 契约。
- **`DefaultStorageProviderFactory`**：结合 `AuthenticationProvider` 提取身份并构建对应工作区前缀的 `AliyunOssStorage`。
- **`AliyunOssStorage`**：`StorageProvider` 的阿里云 OSS 实现。提供 `trash(path)` 移动文件/目录到 `.trash/{timestamp}/{path}` 的软删除能力。**安全核心**在 `getFullKey(path)` 方法：

```java
private String getFullKey(String path) {
    if (!StringUtils.hasText(path)) {
        return this.prefix;
    }
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
- **`HarnessMcpServerAutoConfiguration`**：
  - 配置 Stateless MCP Server 传输层，通过 `contextExtractor` 将 `ServerRequest` 注入 `McpTransportContext`
  - 自动装配 `@ConditionalOnMissingBean` 的 `AuthenticationProvider`（默认 `HeaderAuthenticationProvider`）
  - 自动装配 `@ConditionalOnMissingBean` 的 `StorageProviderFactory`（默认 `DefaultStorageProviderFactory`）

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
- **禁止在代码中硬编码类的全限定名（FQCN）**：代码中应通过 `import` 显式引入类，避免在方法签名、变量类型声明或 `new` 实例化时直接使用完整的 `packageName.ClassName`
- **常量值修改需同步 prompt**：`StorageProvider` 中标注了「如有变动，需同步修改 prompt」的常量（如 `MAX_LINES`、`MAX_LINE_LENGTH`）与 `@McpTool` 的 description 文本耦合，修改时务必两侧同步

### 分层职责

| 层 | 职责 | 安全与防边界 |
|----|------|--------------|
| `auth/` | 请求身份提取与校验，生成标准 `WorkspaceIdentity` | Header 解析、Token 清洗、格式错误拦截 |
| `tool/` | MCP 工具定义与参数校验，使用 `StorageProviderFactory` 获取隔离存储 | 参数格式防错、不合法路径拦截 |
| `storage/` | 存储抽象、工厂模式与 OSS 实现，提供文件读写、ListDirectory 及 Trash 回收站能力 | 路径隔离、绝对路径拦截 |
| `autoconfig/` | Spring Boot 自动配置，装配 Server、Auth 与 StorageFactory Bean | 配置注入、可插拔组件替换 |

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
