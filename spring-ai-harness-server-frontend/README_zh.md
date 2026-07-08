# Spring AI Harness Server Frontend Web UI (中文文档)

该项目是基于 React 构建的 Windows 资源管理器风格的文件管理 Web 控制台。它与 `spring-ai-harness-mcp-server` 后端的 REST API 进行通信，允许开发人员和管理员管理工作区文件、在浏览器中编辑文件内容以及回滚文件版本快照。

[English](README.md)

## 核心特性

- **Windows 资源管理器范式**：包含面包屑地址栏、目录列表/网格卡片视图、文件重命名、创建和删除。
- **拖拽移动**：原生 HTML5 拖拽支持，通过将文件/目录拖动到文件夹或父级面包屑项上来移动它们。
- **代码编辑器抽屉**：浏览器内的代码预览与编辑能力，支持保存并自动触发前置安全快照。
- **快照回滚抽屉**：展示版本历史记录及对应的事务事件标签（`WRITE`、`EDIT`、`TRASH`、`MOVE`、`REWIND`），支持一键秒级回滚。
- **双重管理模式**：
  - **用户工作区模式**：通过用户 Authorization 令牌访问隔离的独立文件夹。
  - **管理员模式**：使用 `X-Admin-Token` 验证，列出、浏览、重命名和删除所有 OSS 工作区上下文中的文件。
- **MCP 客户端调试器**：集成调试页面，用于浏览后端工具、通过交互式 JSON-RPC 编辑器执行工具负载，并检查原始传输流量。

## 技术栈

- **核心框架**：React 18
- **UI 组件库**：Ant Design (`antd` v5) 与 `@ant-design/icons`
- **主题风格**：深色暗黑毛玻璃风格（Glassmorphism）
- **打包工具**：Vite (高优化性能的 Esbuild/Rollup 构建系统)

## 如何安装与运行

确保你已安装 Node.js (18 或更高版本) 和 npm。

### 1. 安装依赖
在当前目录下执行：
```bash
npm install
```

### 2. 启动开发服务器
```bash
npm run dev
```
使用 Vite 在本地开发模式下运行应用，服务地址为 [http://localhost:3000](http://localhost:3000)，支持热重载（HMR）。

### 3. 生产环境编译
```bash
npm run build
```
这将在 `dist/` 文件夹中构建出优化后的静态资源包，随时可以部署到生产环境。

## 代理配置说明
默认情况下，Vite 开发服务器配置了反向代理，将匹配 `/api` 和 `/mcp` 的请求转发到位于 `http://localhost:8080` 的后端 Spring Boot 服务器。如果后端端口发生更改，请调整 `vite.config.js` 中的配置。
