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

## Tech Stack & Design Rules
- **Framework**: React 18 (JavaScript) with Vite build system.
- **UI Library**: Ant Design (`antd` v5) + `@ant-design/icons`.
- **Styling**: Modern dark glassmorphism theme (`index.css`), smooth hover transitions, vibrant action tags.
- **State & Drag & Drop**: Native HTML5 Drag & Drop API for moving files/folders across breadcrumb paths and target folders.
- **MCP Client Debugging**: Calls standard JSON-RPC 2.0 methods (`tools/list`, `tools/call`, `resources/list`, `resources/read`) on `/mcp` via HTTP POST, displaying the raw wire traffic in a split inspector view.

## Available Scripts
- `npm run dev`: Runs the app in development mode at http://localhost:3000
- `npm run build`: Bundles the app for production in the `dist/` folder.
