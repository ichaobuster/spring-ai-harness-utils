# AGENTS.md — spring-ai-harness-server-frontend

Spring AI Harness Server Web UI Manager module for OSS File & Workspace Management.

## Module Architecture

```
spring-ai-harness-server-frontend/
├── .agents/
│   └── AGENTS.md                # Dedicated frontend documentation & guidelines
├── config/                      # Webpack and Jest configuration files (Ejected CRA)
│   ├── env.js
│   ├── webpack.config.js
│   └── paths.js
├── scripts/                     # Start, build, and test scripts (Ejected CRA)
│   ├── start.js
│   ├── build.js
│   └── test.js
├── public/
│   ├── favicon.ico
│   └── index.html               # Entry HTML with custom fonts & viewport settings
├── src/
│   ├── components/
│   │   ├── FileExplorer.js      # Main Windows Explorer style file manager
│   │   ├── FileListTable.js     # List view component with Ant Design Table
│   │   ├── FileGridCards.js     # Grid view component with Ant Design Cards
│   │   ├── FileViewerModal.js   # In-browser text editor & preview modal
│   │   ├── SnapshotDrawer.js    # Snapshot history & Rewind drawer
│   │   └── NewItemModal.js      # Modal for creating new text files & folders
│   ├── services/
│   │   └── api.js               # Axios REST client for Workspace & Admin APIs
│   ├── styles/
│   │   └── index.css            # Dark mode tokens, glassmorphism, micro-animations
│   ├── App.js                   # Root React component with Ant Design ConfigProvider
│   └── index.js                 # React DOM root entry
└── package.json                 # Ejected CRA React project configuration
```

## Tech Stack & Design Rules
- **Framework**: React 18 (JavaScript) with CRA Ejected Webpack structure.
- **UI Library**: Ant Design (`antd` v5) + `@ant-design/icons`.
- **Styling**: Modern dark glassmorphism theme (`index.css`), smooth hover transitions, vibrant action tags.
- **State & Drag & Drop**: Native HTML5 Drag & Drop API for moving files/folders across breadcrumb paths and target folders.

## Available Scripts
- `npm start`: Runs the app in development mode at http://localhost:3000
- `npm run build`: Bundles the app for production in the `build/` folder.
