# spring-ai-harness-mcp-server 功能分析报告

## 一、用户已提出的功能分析

### 1. 🔐 认证模块拆分

**现状**：`FileSystemTools.getStorageProvider()` 中硬编码了 Authorization 解析逻辑，仅以 `-` 分割获取 `{system}-{agent}-{user}` 三元组。

**问题**：
- 认证逻辑与工具逻辑耦合在 `FileSystemTools` 中
- 明文传递 Authorization 没有安全保障
- `system-agent-user` 如果其中任一字段本身含有 `-`，则分割失败
- 没有 token 校验、签名验证、过期机制
- 每次 `@McpTool` 方法调用都重复执行解析逻辑

**建议方案**：

```
io.github.springai.harness/
├── auth/
│   ├── AuthenticationProvider.java          # 认证抽象接口
│   ├── PlainTextAuthenticationProvider.java  # 当前明文实现（过渡用）
│   ├── JwtAuthenticationProvider.java        # JWT 实现（未来）
│   ├── WorkspaceIdentity.java               # record(system, agent, user) 数据类
│   └── AuthenticationFilter.java            # Spring WebMvc Filter，统一拦截认证
```

**关键设计决策**：
- 使用 Spring `HandlerInterceptor` 或 `Filter` 在请求进入 MCP 层之前完成认证
- 将 `WorkspaceIdentity` 注入 `McpTransportContext` 或 `RequestAttributes`
- `FileSystemTools` 只需从 context 中获取已解析的身份信息
- 分隔符建议改为不易出现在标识符中的字符（如 `:`、`|`），或直接使用结构化格式（Base64 编码的 JSON）

**优先级**：🔴 高 — 这是后续所有功能的基础

---

### 2. 🗑️ Trash（软删除）功能

**现状**：`StorageProvider` 有 `delete()` 方法但执行的是永久删除。

**建议方案**：

```java
// StorageProvider 接口新增
void trash(String path) throws IOException;
List<TrashItem> listTrash() throws IOException;
void restoreFromTrash(String trashItemId, String restorePath) throws IOException;
void emptyTrash() throws IOException;

record TrashItem(String id, String originalPath, long size, long trashedAt) {}
```

- Trash 路径：`{prefix}/.trash/{timestamp}_{originalFileName}`
- 保留原始路径元数据（可用 OSS object metadata 或 sidecar JSON 文件记录）
- 可配置自动清理策略（如 7 天后自动清除）

**与快照功能的关系**：
- Trash 解决的是**显式删除操作**的恢复问题
- 快照解决的是**文件内容修改**的回滚问题
- **两者互补而非重复**：Trash 管理的是被删除的文件，快照管理的是被修改的文件版本
- 但若快照方案已覆盖删除操作前的状态，则 Trash 可作为轻量级的快捷恢复手段

**优先级**：🟡 中 — 简单实用，可快速实现

---

### 3. 📸 文件快照 / Rewind 能力

**现状**：无任何版本控制或快照机制。

**业界参考实现对比**：

| Agent | 快照机制 | 触发时机 | 存储位置 | 回滚方式 |
|-------|---------|---------|---------|----------|
| **Claude Code** | 文件级版本快照 | 每次用户发送 prompt 时 | `~/.claude/file-history/{sessionId}/` | `/rewind` 交互式菜单 |
| **Hermes** | 自动快照 | 文件修改前 | 本地文件系统 | `/rollback` 命令 |
| **Antigravity** | Git checkpoint | 文件修改前 | Git 仓库 | `/rewind` 命令 |

**Claude Code `/rewind` 实现细节**（核心参考）：
- 文件版本存储为 `filename_hash@v1`、`v2` 等递增版本
- **增量快照**：通过文件元数据（size、mtime）+ 字节级比较判断是否真正修改，仅保存实际变更的文件
- **权限保留**：回滚时恢复文件权限（chmod）
- **回滚模式**支持 4 种选项：
  1. 恢复代码 + 对话历史
  2. 仅恢复对话
  3. 仅恢复代码
  4. 压缩对话为摘要
- **局限**：仅跟踪内置编辑工具的修改，Bash 命令（`rm`、`mv`）和外部编辑不被跟踪

**MCP Server 上的可行方案**：

| 方案 | 实现方式 | 优点 | 缺点 |
|------|---------|------|------|
| **A. OSS 版本控制** | 开启 Bucket Versioning | 零额外开发，OSS 原生支持 | 粒度为单文件，无法按操作聚合；版本列表管理复杂 |
| **B. 自建快照** | 每次 write/edit 前将当前内容保存到 `{prefix}/.snapshots/{snapshotId}/` | 可按操作聚合，支持整体 rewind | 存储成本较高，需自行管理生命周期 |
| **C. 混合方案** | 利用 OSS Versioning 存储文件版本 + 自建 snapshot 元数据索引操作记录 | 兼顾原生能力和操作聚合 | 实现复杂度最高 |

**推荐方案 B（自建快照）**，参考 Claude Code 的增量策略减少存储开销：

```java
// 新增接口
public interface SnapshotProvider {
    /** 在执行修改操作前调用，创建快照点 */
    String createSnapshot(String description) throws IOException;
    
    /** 列出所有快照 */
    List<SnapshotInfo> listSnapshots(int limit) throws IOException;
    
    /** 回滚到指定快照 */
    void rewind(String snapshotId) throws IOException;
    
    record SnapshotInfo(String id, String description, long createdAt, 
                        List<String> affectedFiles) {}
}
```

```java
// FileSystemTools 中的 Write 方法调用前自动创建快照
@McpTool(name = "Write", ...)
public String write(McpTransportContext context, String filePath, String content) {
    StorageProvider sp = getStorageProvider(context);
    snapshotProvider.createSnapshot("Before Write: " + filePath);  // 自动快照
    // ... 执行写入
}
```

> [!NOTE]
> 与 Claude Code 不同，MCP Server 中**所有文件操作都经过 MCP 工具**，因此可以 100% 跟踪所有修改，不存在 Claude Code 中 Bash 命令导致的跟踪盲区。但 Shell/Browser 沙箱中的文件操作仍需通过沙箱 hook 或 mount 回写时统一处理。

**新增 MCP 工具**：

```java
@McpTool(name = "ListSnapshots", ...)
public String listSnapshots(McpTransportContext context, Integer limit);

@McpTool(name = "Rewind", ...)
public String rewind(McpTransportContext context, String snapshotId);
```

**优先级**：🟡 中 — 价值很高但实现复杂度较大

---

### 4. 📚 Skills 管理

**现状**：无 Skills 相关功能。

#### 各 Agent 的 Skills 处理方式研究

| Agent | Skills 格式 | 加载方式 |
|-------|-----------|---------|
| **Claude Code / Antigravity** | `SKILL.md`（YAML frontmatter + Markdown body），放在 `skills/<name>/` 目录下 | 自动扫描 `.agents/skills/` 或配置文件注册 |
| **OpenClaw** | 类似 Claude Code 的 skills 目录结构 | 读取工作区下的约定目录 |
| **Hermes** | Plugin 机制，支持多种 hook | 运行时加载 plugin 配置 |
| **QwenPaw** | 工具定义 + prompt 模板 | 框架层面注册 |

#### 通过 MCP 提供 Skills 的三种途径

| MCP 能力 | 适用场景 | 方案 |
|---------|---------|------|
| **Tools** | 提供一个 `ReadSkill` 工具让 agent 主动读取 | Agent 调用 `ReadSkill(skillName)` → 返回 SKILL.md 内容 |
| **Prompts** | 将 Skills 注册为 MCP Prompts，agent 可列出和获取 | 每个 skill 注册为一个 `prompt`，agent 通过 `prompts/list` 和 `prompts/get` 获取 |
| **Resources** | 将 Skills 作为 MCP Resources 暴露 | 每个 skill 文件注册为 `resource`，agent 通过 `resources/list` 和 `resources/read` 获取 |

**推荐方案：Tools + Resources 结合**

```java
// MCP Tool：列出和读取 Skills
@McpTool(name = "ListSkills", description = "List available skills")
public String listSkills(McpTransportContext context);

@McpTool(name = "ReadSkill", description = "Read a skill's content")
public String readSkill(McpTransportContext context, String skillName);
```

同时将 skills 注册为 MCP Resources，支持 Agent 原生的 resource 发现机制。

#### OSS Symbolic Links 可行性分析

阿里云 OSS **支持** Symbolic Links（软链接）：
- 通过 `putSymlink` API 创建，`getSymlink` API 读取
- 软链接本身是一个特殊的 OSS 对象，指向目标对象
- **限制**：
  - 软链接只能指向**同一 Bucket** 内的对象
  - 软链接指向的是**单个对象**，不支持目录级别的链接
  - 读取软链接时会自动跟随到目标对象
  - 对软链接的写入会覆盖目标对象（**不安全！**）

> [!WARNING]
> **OSS 软链接不适合实现只读公共 Skills**。因为对软链接执行写操作会直接修改目标对象（即公共 Skills 原文件），存在安全风险。

**替代方案**：
1. **复制方式**：将公共 Skills 定期同步复制到用户 workspace 的 `/skills/.global/` 下（只读通过应用层控制）
2. **双前缀查询**：Skills 工具在读取时同时查询用户目录和公共目录，公共目录的内容通过代码逻辑强制只读
3. **MCP Resources 方式**：公共 Skills 通过 MCP Resources 暴露（天然只读），用户私有 Skills 通过 Tools 暴露

**推荐替代方案 2（双前缀查询）+ 方案 3（MCP Resources）**

**优先级**：🟡 中

---

### 5. 📡 管理功能 REST API

**现状**：仅有 MCP 端点（`/mcp`），无管理 API。

**建议方案**：

```
REST API 结构：

# 用户 API（需用户认证）
GET    /api/v1/workspace/files?path=             # 列出文件
GET    /api/v1/workspace/files/download?path=     # 下载文件
POST   /api/v1/workspace/files/upload?path=       # 上传文件
DELETE /api/v1/workspace/files?path=              # 删除文件
GET    /api/v1/workspace/snapshots                # 列出快照
POST   /api/v1/workspace/rewind/{snapshotId}      # 回滚快照

# 管理员 API（需管理员认证）
GET    /api/v1/admin/workspaces                   # 列出所有 workspace
GET    /api/v1/admin/workspaces/{system}/{agent}/{user}/files?path=
DELETE /api/v1/admin/workspaces/{system}/{agent}/{user}/files?path=
GET    /api/v1/admin/skills                       # 管理公共 Skills
POST   /api/v1/admin/skills                       # 上传公共 Skill
DELETE /api/v1/admin/skills/{skillName}            # 删除公共 Skill
```

**关键设计**：
- 用户 API 复用认证模块识别身份，自动路由到对应 workspace
- 管理员 API 需要额外的 RBAC 权限控制
- 文件上传/下载使用流式传输，避免大文件内存溢出
- 可添加 Swagger/OpenAPI 文档

**优先级**：🟡 中 — 运维和用户体验的重要补充

---

## 二、代码分析发现的额外功能需求

### 6. 🛡️ 路径安全加固（关键！）

**现状分析**：

`AliyunOssStorage.getFullKey()` 当前实现：
```java
private String getFullKey(String path) {
    if (path.startsWith("/")) {
        throw new SecurityException("Absolute paths are not allowed");
    }
    if (path.startsWith("./")) {
        return this.prefix + path.substring(2);
    }
    return this.prefix + path;
}
```

**已发现的漏洞**：

| 攻击向量 | 示例 | 当前是否防御 |
|---------|------|------------|
| 绝对路径 | `/etc/passwd` | ✅ 已拦截 |
| `..` 遍历 | `../../other-user/secret.txt` | ❌ **未防御** |
| `./..` 变体 | `./../../other-user/` | ❌ **未防御** |
| 编码绕过 | `..%2F..%2F` | ⚠️ 依赖 Web 框架解码 |
| 空字节注入 | `file.txt%00.jpg` | ⚠️ 依赖 OSS SDK 处理 |
| 连续斜杠 | `foo//../../bar` | ❌ **未防御** |

**建议修复**：

```java
private String getFullKey(String path) {
    if (!StringUtils.hasText(path)) {
        return this.prefix;
    }
    // 1. 拒绝绝对路径
    if (path.startsWith("/")) {
        throw new SecurityException("Absolute paths are not allowed: '" + path + "'");
    }
    // 2. 规范化：去除 ./ 前缀
    String normalized = path.startsWith("./") ? path.substring(2) : path;
    // 3. 规范化：处理连续斜杠
    normalized = normalized.replaceAll("/+", "/");
    // 4. 拒绝路径遍历
    if (normalized.contains("..")) {
        throw new SecurityException("Path traversal is not allowed: '" + path + "'");
    }
    // 5. 拒绝以 . 开头的隐藏路径（保护 .trash、.snapshots 等内部目录）
    // （可选，视需求而定）
    return this.prefix + normalized;
}
```

**优先级**：🔴 高 — 安全漏洞，应最优先修复

---

### 7. 📂 缺失的文件系统工具

对比 Claude Code / Antigravity 提供的工具集，当前 MCP Server 缺少以下常用工具：

| 缺失的工具 | 功能 | 对标工具 | 优先级 |
|-----------|------|---------|-------|
| **LS / ListDirectory** | 列出目录内容（含文件大小、类型等） | Claude Code `LS` | 🔴 高 |
| **MultiEdit** | 一次请求执行多处编辑（减少往返） | Claude Code `MultiEdit` | 🟡 中 |
| **Move / Rename** | 移动或重命名文件/目录 | 通用文件操作 | 🟡 中 |
| **Delete / Trash** | 删除文件（见第 2 点） | Claude Code `rm` | 🟡 中 |
| **MkDir** | 创建目录 | 通用文件操作 | 🟢 低（OSS 自动创建前缀） |
| **FileInfo** | 获取文件元信息（大小、修改时间等） | 通用文件操作 | 🟢 低 |
| **Tree** | 递归显示目录树结构 | 常见开发工具 | 🟡 中 |

> [!NOTE]
> `StorageProvider` 接口中已经定义了 `listDirectory()`、`rename()`、`delete()`、`getInfo()` 方法，但 `FileSystemTools` 中尚未暴露为 `@McpTool`。这些应该是最容易添加的工具。

**优先级**：🔴 高（LS）/ 🟡 中（其他）

---

### 8. ⚙️ Spring Boot Auto-Configuration 注册

**现状**：缺少 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 文件。

如果此模块打算作为 Spring Boot Starter 被其他项目引用，需要注册自动配置类：

```
# META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
io.github.springai.harness.autoconfig.AliyunOssAutoConfiguration
io.github.springai.harness.autoconfig.HarnessMcpServerAutoConfiguration
```

**优先级**：🟡 中 — 决定本模块是独立部署还是作为 library 嵌入

---

### 9. 📊 操作审计日志

**现状**：仅有 `@Slf4j` 基础日志，无结构化审计。

**建议**：
- 记录每次 MCP 工具调用：who（身份三元组）、what（工具名+参数）、when（时间戳）、result（成功/失败）
- 可用于安全审计、用量统计、异常行为检测
- 存储方式：OSS 日志文件 / 数据库 / 日志平台

**优先级**：🟡 中

---

### 10. 🔧 代码中的 TODO 清单

从源码中提取的现有 TODO 项：

| 文件 | TODO 内容 | 说明 |
|------|----------|------|
| [FileSystemTools.java](file:///Users/ichaobuster/Developer/spring-ai-harness-utils/spring-ai-harness-mcp-server/src/main/java/io/github/springai/harness/tool/FileSystemTools.java#L46) | `// TODO 解析key` | Authorization 头解析逻辑待完善 |
| [AliyunOssStorage.java](file:///Users/ichaobuster/Developer/spring-ai-harness-utils/spring-ai-harness-mcp-server/src/main/java/io/github/springai/harness/storage/AliyunOssStorage.java#L45) | `// TODO 是否要判断 "\" 及多个 "/" 的情况` | 路径规范化待处理 |
| [HarnessMcpServerAutoConfiguration.java](file:///Users/ichaobuster/Developer/spring-ai-harness-utils/spring-ai-harness-mcp-server/src/main/java/io/github/springai/harness/autoconfig/HarnessMcpServerAutoConfiguration.java#L30) | `// TODO 自定义contextExtractor，Spring AI 有默认实现后考虑删除` | 等待 Spring AI 上游支持 |

---

### 11. 🧪 测试覆盖补充

**现状**：有基础单元测试，但覆盖不足。

**需要补充的测试**：
- 路径遍历攻击测试（`..`、`./..`、编码绕过等）
- Authorization 头各种异常格式测试
- 大文件读写边界测试
- 并发操作测试
- Glob/Grep 边界条件测试（空目录、超大文件、二进制文件等）
- 集成测试（可使用 Testcontainers + MinIO 模拟 OSS）

**优先级**：🟡 中

---

### 12. 🌐 错误处理与国际化

**现状**：错误消息为硬编码的英文字符串，直接返回给 agent。

**建议**：
- 统一错误码体系
- 错误信息结构化（便于 agent 解析和处理）
- 考虑是否需要国际化（鉴于 agent 可处理多语言，优先级较低）

**优先级**：🟢 低

---

## 三、功能优先级总览

```mermaid
quadrantChart
    title 功能优先级矩阵
    x-axis 实现难度低 --> 实现难度高
    y-axis 业务价值低 --> 业务价值高
    quadrant-1 优先实施
    quadrant-2 规划实施
    quadrant-3 考虑实施
    quadrant-4 按需实施
    路径安全加固: [0.2, 0.95]
    认证模块拆分: [0.4, 0.9]
    LS工具补全: [0.15, 0.8]
    Trash功能: [0.3, 0.65]
    文件快照: [0.7, 0.85]
    Skills管理: [0.65, 0.7]
    管理REST API: [0.6, 0.6]
    操作审计日志: [0.45, 0.55]
    AutoConfig注册: [0.1, 0.5]
    测试覆盖: [0.35, 0.5]
    MultiEdit工具: [0.35, 0.4]
    错误处理: [0.25, 0.3]
```

### 推荐实施顺序

| 阶段 | 功能 | 理由 |
|------|------|------|
| **P0 — 安全底线** | 路径安全加固 (#6) | 当前存在路径遍历漏洞，必须立即修复 |
| **P1 — 基础设施** | 认证模块拆分 (#1) | 后续所有功能（管理 API、Skills 权限控制等）的前置依赖 |
| **P1 — 基础设施** | LS 等缺失工具 (#7) | 核心文件操作能力补全，已有接口实现只需暴露为 McpTool |
| **P2 — 核心功能** | Trash 功能 (#2) | 实现简单，安全价值高 |
| **P2 — 核心功能** | 文件快照/Rewind (#3) | Agent 安全操作的关键保障 |
| **P3 — 扩展功能** | Skills 管理 (#4) | 提升 agent 能力的重要特性 |
| **P3 — 扩展功能** | 管理 REST API (#5) | 运维和用户自助管理 |
| **P4 — 质量提升** | 操作审计、测试覆盖、错误处理 | 稳定性和可维护性 |

---

## 四、关于用户提出的开放性问题

### Q: Trash 与 快照是否重复？

**结论：不重复，互补关系。**

| 场景 | Trash | 快照 |
|------|-------|------|
| Agent 调用 Delete 删除了文件 | ✅ 可从 .trash 恢复 | ✅ 可从快照恢复 |
| Agent 调用 Write 覆写了文件 | ❌ 文件未被删除 | ✅ 可从快照恢复修改前版本 |
| Agent 调用 Edit 修改了文件 | ❌ 文件未被删除 | ✅ 可从快照恢复修改前版本 |
| 用户想快速恢复刚删的文件 | ✅ 一步操作 | ⚠️ 需要找到包含该文件的快照 |
| 用户想回滚到某个时间点 | ❌ 只管理被删文件 | ✅ 全量恢复到指定时间 |

**建议**：两个功能都实现，Trash 作为轻量级的删除恢复快捷方式，快照作为全量的时间点回滚能力。

### Q: OSS 软链接用于公共 Skills 是否可行？

**结论：不可行，存在安全风险。**

OSS 软链接的写操作会穿透到目标对象，Agent 如果对 Skill 文件执行写操作，会直接修改公共 Skills 库原文件。建议使用「双前缀查询 + 应用层只读控制」的替代方案。
