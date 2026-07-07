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

## Tech Stack

- **Framework**: React 18
- **UI Toolkit**: Ant Design (`antd` v5) & `@ant-design/icons`
- **Theme**: Curated dark glassmorphism styling
- **Packaging**: Ejected Create React App (Webpack/Babel configurations exposed)

## How to Install & Run

Ensure you have Node.js (version 18 or higher) and npm installed.

### 1. Install dependencies
From this directory:
```bash
npm install
```

### 2. Start the development server
```bash
npm start
```
This runs the app in development mode at [http://localhost:3000](http://localhost:3000). The browser will open automatically and hot reload on file changes.

### 3. Build for production
```bash
npm run build
```
This builds the optimized static app bundle in the `build/` folder, ready for deployment.

## Proxy Configuration
By default, the Webpack dev server configures a reverse proxy to forward requests under `/api/` to the backend server at `http://localhost:8080`. Adjust configurations inside `config/webpackDevServer.config.js` or via `.env` file if your backend port changes.
