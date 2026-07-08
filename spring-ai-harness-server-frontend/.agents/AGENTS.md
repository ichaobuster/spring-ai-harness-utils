# AGENTS.md — spring-ai-harness-server-frontend

Spring AI Harness Server Web UI Manager module for OSS File & Workspace Management.

## Module Architecture

```
spring-ai-harness-server-frontend/
├── .agents/
│   └── AGENTS.md                # Dedicated frontend documentation & guidelines
├── index.html                   # Entry HTML with custom fonts & script injection
├── vite.config.js               # Vite project configuration with proxies & react plugin
├── src/
│   ├── components/
│   │   ├── FileExplorer.js      # Main Windows Explorer style file manager
│   │   ├── FileListTable.js     # List view component with Ant Design Table
│   │   ├── FileGridCards.js     # Grid view component with Ant Design Cards
│   │   ├── FileViewerModal.js   # In-browser text editor & preview modal
│   │   ├── SnapshotDrawer.js    # Snapshot history & Rewind drawer
│   │   ├── NewItemModal.js      # Modal for creating new text files & folders
│   │   └── McpDebugger.js       # MCP Client debugger panel with JSON-RPC wire inspector
│   ├── services/
│   │   └── api.js               # Axios REST client (Workspace/Admin APIs & /mcp client calls)
│   ├── styles/
│   │   └── index.css            # Dark mode tokens, glassmorphism, micro-animations
│   ├── App.js                   # Root React layout containing the navigation header
│   └── index.js                 # React DOM root entry
└── package.json                 # React project configuration with Vite scripts
```

---

## Tech Stack & Design Rules

- **Framework**: React 18 (JavaScript) with Vite build system.
- **UI Library**: Ant Design (`antd` v5) + `@ant-design/icons`.
- **Styling**: Modern dark glassmorphism theme (`index.css`), smooth hover transitions, vibrant action tags.
- **State & Drag & Drop**: Native HTML5 Drag & Drop API for moving files/folders across breadcrumb paths and target folders.
- **MCP Client Debugging**: Calls standard JSON-RPC 2.0 methods (`tools/list`, `tools/call`, `resources/list`, `resources/read`) on `/mcp` via HTTP POST, displaying the raw wire traffic in a split inspector view.

---

## 前端编码与设计规范

### UI & 组件准则
- **Ant Design 优先**：禁止随意手写基础布局，优先选择并复用 Ant Design v5 的排版与布局组件（如 `Space`, `Flex`, `Row`, `Col`, `Table`, `Card`）。
- **样式统一性**：不引入 TailwindCSS 或 heavy CSS-in-JS 库，完全基于 `styles/index.css` 的全局暗黑毛玻璃风格（Glassmorphism）和统一的自定义 Theme Token（避免硬编码 Hex 颜色）。
- **图标使用**：一律使用 `@ant-design/icons`。引入时应进行单组件按需引入，以便打包器（Vite/Rollup）进行 tree-shaking 优化。
- **表单验证**：复杂表单建议使用 Ant Design 的 `Form` 组件以及内置校验规则，利于开发和维护。

### 代码风格约定
- **组件模式**：推荐 Functional Component + React Hooks 结构。复杂计算或子组件 Props 传递时，使用 `useMemo` 与 `useCallback` 避免多余的重渲染。
- **命名规范**：
  * 组件文件统一使用 `PascalCase`（例如 `FileListTable.js`）
  * 自定义 Hook 统一使用首字母小写的 `camelCase`，并以 `use` 开头。
  * 辅助函数或非组件文件使用 `kebab-case`。
- **解构传参**：组件 Props 建议直接在函数签名处进行解构，提升代码可读性。
- **最小化状态污染**：避免不必要的状态同步或过度嵌套的状态树设计。

---

## Available Scripts

- `npm run dev`: Runs the app in development mode at http://localhost:3000
- `npm run build`: Bundles the app for production in the `dist/` folder.
- `npm run preview`: Previews the production build locally.

---

## 测试与质量要求

- **单测目标**：对公共组件、自定义 Hooks 以及辅助函数在有条件时编写测试，确保前端核心工具函数和数据清洗的健壮性。
- **业务逻辑覆盖率**：目标覆盖率应达到 **80%**。

---

## AI 助手开发建议与质量红线

1. **样式约束**：切勿引入未经请求的第三方样式库（如 TailwindCSS）。始终重用已有的 Glassmorphism 样式与暗黑主题 Token。
2. **状态更新性能**：注意 React 18 状态批处理（state batching）特点，避免过度触发 re-render 影响文件列表大视图的拖拽流畅度。
3. **接口类型安全**：调用 `services/api.js` 发送请求时，注意校验参数边界，尤其是文件绝对路径在底层会导致 API 报错。
4. **外科手术式修改**：修改组件代码时保持组件自身样式缩进的一致，减少非必要的格式变动。
