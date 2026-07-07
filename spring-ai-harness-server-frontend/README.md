# Spring AI Harness Server Frontend Web UI

This project is a React-based File Manager Web Console styled like Windows Explorer. It communicates with the backend REST APIs of `spring-ai-harness-mcp-server` to allow developers and administrators to manage workspace files, edit file contents in-browser, and rewind file version snapshots.

## Key Features

- **Windows Explorer Paradigm**: Features breadcrumb address bar, directory list/grid card views, file rename, creation, and deletion.
- **Drag & Drop Moving**: Native HTML5 Drag & Drop support to move files/directories by dragging them onto folders or parent breadcrumb items.
- **Code Editor Drawer**: In-browser code preview and edit capabilities with saving and automatic safety snapshot triggers.
- **Snapshot Rollback Drawer**: Displays version history with transaction event tags (`WRITE`, `EDIT`, `TRASH`, `MOVE`, `REWIND`) and one-click rollback.
- **Dual Management Modes**:
  - **User Workspace Mode**: Accesses isolated folder via user Authorization token.
  - **Admin Mode**: Validates with `X-Admin-Token` to list, browse, rename, and delete files across all OSS workspace contexts.
- **MCP Client Debugger**: Integrated debugging page to browse backend tools, execute tool payloads via interactive JSON-RPC editor, and inspect raw wire traffic.

## Tech Stack

- **Framework**: React 18
- **UI Toolkit**: Ant Design (`antd` v5) & `@ant-design/icons`
- **Theme**: Curated dark glassmorphism styling
- **Packaging**: Vite (highly optimized Esbuild/Rollup build system)

## How to Install & Run

Ensure you have Node.js (version 18 or higher) and npm installed.

### 1. Install dependencies
From this directory:
```bash
npm install
```

### 2. Start the development server
```bash
npm run dev
```
This runs the app in local development mode using Vite, serving at [http://localhost:3000](http://localhost:3000) with instant hot reloading.

### 3. Build for production
```bash
npm run build
```
This builds the optimized static app bundle in the `dist/` folder, ready for production deployment.

## Proxy Configuration
By default, the Vite dev server configures a reverse proxy to forward requests matching `/api` and `/mcp` to the backend Spring Boot server at `http://localhost:8080`. Adjust configurations inside `vite.config.js` if the backend port changes.
