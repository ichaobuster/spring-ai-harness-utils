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
# 导出形式: otlp, none (默认: otlp)
spring.ai.harness.mcp.server.observability.export-type=otlp
```

### 1.3 后端编码与质量规范

#### 编码风格约定
- **日志规范**：严禁使用 `System.out.println` 等控制台直接输出，必须使用 SLF4J 接口（通过 Lombok `@Slf4j` 注解）记录结构化日志（`log.info`, `log.error`）。
- **Java 17 特性**：数据传输对象（DTO）和只读元数据模型优先使用 Java 17 `record` 声明，确保不可变性。
- **参数验证**：接口参数输入层需使用 Jakarta Bean Validation（如 `@NotNull`, `@Size`）进行字段边界约束。
- **工具描述**：`@McpTool` 标注的方法描述（`description`）以及其参数注解中的说明必须为**英文**（便于大语言模型直接理解），而代码业务注释须使用**中文**。

#### 单元测试与覆盖率红线
- **全面覆盖**：每一个新建的业务逻辑或工具类必须编写对应的 JUnit 5 测试类。
- **覆盖率红线**：核心逻辑类（如路径解析、权限校验等）的**行覆盖率（Line Coverage）**与**分支覆盖率（Branch Coverage）**必须达到 **80%** 以上。
- **Mocking 策略**：所有涉及到 `AliyunOssStorage` 的测试必须通过 Mockito 进行全量 Mock（包括 `OSS` 客户端与网络请求），严禁连接物理阿里云 OSS 存储。

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

### 2.3 前端编码与设计规范

#### UI & 组件准则
- **Ant Design 优先**：禁止随意手写基础布局，优先选择并复用 Ant Design v5 的排版与布局组件（如 `Space`, `Flex`, `Row`, `Col`, `Table`, `Card`）。
- **样式统一性**：不引入 TailwindCSS 或 heavy CSS-in-JS 库，完全基于 `styles/index.css` 的全局暗黑毛玻璃风格（Glassmorphism）和统一的自定义 Theme Token（避免硬编码 Hex 颜色）。
- **图标使用**：一律使用 `@ant-design/icons`。引入时应进行单组件按需引入，以便打包器（Vite/Rollup）进行 tree-shaking 优化。

#### 代码风格约定
- **组件模式**：推荐 Functional Component + React Hooks 结构。复杂计算或子组件 Props 传递时，使用 `useMemo` 与 `useCallback` 避免多余的重渲染。
- **命名规范**：
  * 组件文件统一使用 `PascalCase`（例如 `FileListTable.js`）
  * 自定义 Hook 统一使用首字母小写的 `camelCase`，并以 `use` 开头。
  * 辅助函数或非组件文件使用 `kebab-case`。
- **解构传参**：组件 Props 建议直接在函数签名处进行解构，提升代码可读性。

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

---

## 4. AI 助手开发建议与质量红线

1. **安全第一（Surgical Edits）**：在修改已有组件或代码逻辑时，务必做“外科手术式”的小范围修改，尽可能不打乱已有的格式与缩进，避免引入任何跨 Workspace 逃逸的文件系统路径。
2. **测试与开发并重**：任何新增加的方法或逻辑修复，完成后应第一时间执行全量单元测试（`./mvnw test`），确认未发生功能退化（Regression）。
3. **配置文件警戒线**：严禁在 `pom.xml`、`package.json` 以及本地构建配置中擅自修改公共 Maven 或 npm 仓库的源地址。
