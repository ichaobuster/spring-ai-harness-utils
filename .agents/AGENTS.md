# Workspace AGENTS.md — Spring AI Harness Utils

> [!IMPORTANT]
> **本项目仍处于积极开发阶段**，存在大量尚未开发完成、需要打磨改进以及需要补充库的功能。
> 本文档是根目录全局的 **Living Document（活文档）**，汇总了后端（mcp-server）与前端（server-frontend）的技术架构与编码规范。
> 各个子模块下独立保留的 `AGENTS.md` 将保持同步更新，改动代码或技术架构时**必须同步维护根目录及子模块下所有相关的 AGENTS.md**。

---

## 根目录架构概述

该项目由两大核心子模块构成：
1. **`spring-ai-harness-mcp-server`**：基于 Spring Boot & Spring AI MCP 实现的无状态服务端，提供工作区安全路径过滤、Snapshot 快照创建与版本回退、可插拔 OTel 链路追踪观测。
2. **`spring-ai-harness-server-frontend`**：基于 React 18 + Ant Design 5 + Vite 构建的管理后台 Web 控制台，包含 Windows 资源管理器风格的文件管理及 MCP Client 调试器。

### 统一设计原则与安全规范
- **禁止在代码中硬编码类的全限定名（FQCN）**：代码中应通过 `import` 显式引入类，避免在方法签名、类型声明或实例化（`new`）时直接使用完整的 `packageName.ClassName` 路径。
- **工作区彻底隔离**：所有文件底层物理操作必须强绑定 `{system}-{agent}-{user}` 前缀，且任何路径在处理前必须经校验，绝对路径（以 `/` 开头）应当被直接拒绝并抛出 `SecurityException`。
- **文档一致性优先**：常量的改动（如读取行数限制等）必须同时维护相关 `@McpTool` 英文 description 中供大模型理解的参数限制，两侧禁止脱节。

---

## 1. 后端模块 (spring-ai-harness-mcp-server)

### 1.1 架构设计与隔离模型

#### 隔离模型示意图
```
oss://{bucket}/{ossPrefix}/{system}-{agent}-{user}/
       │         │           │        │       │
       │         │           │        │       └── 用户维度隔离 (分隔符 -)
       │         │           │        └────────── Agent 维度隔离 (分隔符 -)
       │         │           └─────────────────── 系统维度隔离
       │         └─────────────────────────────── 可配置前缀 (默认 mcp/workspaces/)
       └───────────────────────────────────────── OSS Bucket
```

对于 Agent 来说，pwd 默认定位在 `./`，无法感知自身在物理 OSS 中以 `oss://{bucket}/mcp/workspaces/{system}-{agent}-{user}/` 组织的完整路径。

#### 核心组件职责划分

| 模块包名 | 职责职责 | 安全边界防卫 |
| :--- | :--- | :--- |
| `auth/` | 从 Header 提取和验证身份生成 `WorkspaceIdentity` | Token 清洗、首尾去空、非法格式拦截 |
| `tool/` | 定义 `@McpTool` 工具并处理基本参数校验 | 参数范围检查、防注水 |
| `storage/` | 对物理文件读取、写入及回收站进行操作与工厂装配 | 拦截绝对路径及逃逸逻辑 |
| `snapshot/` | 管理版本控制，每次写入前触发前置及回滚快照 | 快照隐藏、恢复兜底 |
| `autoconfig/` | 组装上述 Bean，并挂载可观测 `ObservationRegistry` 包装 | 轻量级可插拔链路控制 |

### 1.2 必需配置选项
```properties
# 阿里云 OSS 连接
aliyun.oss.endpoint=https://oss-cn-hangzhou.aliyuncs.com
aliyun.oss.access-key-id=<your-access-key-id>
aliyun.oss.access-key-secret=<your-access-key-secret>
spring.ai.harness.mcp.server.oss-bucket=<your-bucket-name>

# 可观测链路追踪 (默认值: false)
spring.ai.harness.mcp.server.observability.enabled=false
# 导出形式: otlp, stdout, none (默认: stdout)
spring.ai.harness.mcp.server.observability.export-type=stdout
```

---

## 2. 前端模块 (spring-ai-harness-server-frontend)

### 2.1 技术栈与架构树
- **核心框架**：React 18 (JavaScript) + Vite 构建链。
- **组件系统**：Ant Design (`antd` v5) 扁平暗黑毛玻璃风格（Glassmorphism）。
- **文件拖拽**：使用 HTML5 原生 Drag & Drop API 实现跨面包屑与目录拖拽重命名移动。
- **构建脚本**：
  * 开发热重载：`npm run dev`（监听端口 3000）
  * 生产编译打包：`npm run build`（输出至 `dist/`）

```
spring-ai-harness-server-frontend/
├── index.html                   # 挂载入口 HTML 与 ESM Script 脚本引入
├── vite.config.js               # Vite 配置文件（包含 JSX Esbuild 转换及代理转发）
├── src/
│   ├── components/
│   │   ├── FileExplorer.js      # Windows 资源管理器界面主组件
│   │   ├── FileListTable.js     # 表格列表视图组件
│   │   ├── FileGridCards.js     # 网格平铺卡片视图组件
│   │   ├── McpDebugger.js       # 内置的 MCP 客户端 JSON-RPC 调试面板
│   │   └── SnapshotDrawer.js    # 侧边栏快照列表及 Rewind 控制台
│   ├── services/
│   │   └── api.js               # 基于 Axios 的后端管理服务及 /mcp 发包器
│   └── App.js                   # 顶层粘性全局导航栏及 View 切换
```

### 2.2 开发服务反向代理
在本地开发（`npm run dev`）时，Vite 的 dev 代理会自动将符合以下规则的流量无感路由到 Spring Boot 后端：
- `/api/**` -> `http://localhost:8080/api/**` (业务 REST API)
- `/mcp` -> `http://localhost:8080/mcp` (Stateless JSON-RPC 端点)

---

## 3. 全局编译与测试命令

### 编译与测试后端
```bash
# 编译 backend
./mvnw compile -pl spring-ai-harness-mcp-server

# 运行 backend 单元测试（单元测试必须全 Mock，不得连接物理 OSS）
./mvnw test -pl spring-ai-harness-mcp-server
```

### 构建运行前端
```bash
# 进入前端文件夹
cd spring-ai-harness-server-frontend

# 安装依赖
npm install

# 离线环境拉取 Windows 对应原生 esbuild/rollup 依赖（仅在 Mac 帮 Windows 制作离线包时使用）
npm install --os=win32 --cpu=x64

# 本地热更新调试
npm run dev

# 编译打包
npm run build
```
