# Workspace AGENTS.md — Spring AI Harness Utils

> [!IMPORTANT]
> **This project is under active development.** There are features still in progress, areas to polish, and libraries to supplement.
> This document is a **Living Document** at the project root, consolidating the technical architecture and coding standards across backend (`mcp-server`), frontend (`server-frontend`), gateway (`mcp-tool-gateway`), shared utils, and executable skills (`spring-ai-skills`) modules.
> Sub-module `AGENTS.md` files are kept in sync. When modifying code or architecture, **all related AGENTS.md files (root and sub-modules) MUST be updated accordingly**.

---

## Root Architecture Overview

The project consists of the following core sub-modules:
1. **`spring-ai-harness-mcp-server`**: A stateless server built on Spring Boot & Spring AI MCP, providing workspace-isolated secure path filtering, Snapshot creation & version rollback, streaming multimedia file processing, and pluggable OTel tracing.
2. **`spring-ai-harness-server-frontend`**: A management Web console built with React 18 + Ant Design 5 + Vite, featuring a Windows Explorer-style file manager and an MCP Client debugger.
3. **`mcp-tool-gateway`**: A stateless MCP gateway server built on Spring Boot that supports configurable header extraction, authentication validation, dynamic tool listing (filtered by headers), and transparent HTTP bypass tool invocation.
4. **`spring-ai-skills`**: A library of executable agent skills exposed as Spring AI `@Tool` beans/classes (Apache POI-based XLSX and DOCX tools), with bundled classpath `SKILL.md` prompts loadable via `SkillUtil`.
5. **`spring-ai-harness-utils`**: Shared library providing `StorageProvider`, harness tools, skill utilities, context-compact advisors, and C2 sensitive-data masking for tool arguments and assistant messages.

### Unified Design Principles & Security Standards
- **No hardcoded FQCNs in code**: Classes must be imported via explicit `import` statements. Using full `packageName.ClassName` paths in method signatures, type declarations, or `new` instantiations is strictly forbidden.
- **Complete workspace isolation**: All underlying physical file operations must bind to the `{system}-{agent}-{user}` prefix. All paths must be validated before processing; absolute paths (starting with `/`) must be rejected with a `SecurityException`.
- **Documentation consistency first**: Any changes to constants (e.g., read line limits, image edge limits) must also update the corresponding `@McpTool` English descriptions for LLM consumption. Both sides must stay in sync.

### C2 Sensitive-Data Masking (`spring-ai-harness-utils`)
- `C2DataMaskingService` defaults to four public recognizers under `masking.recognizer`: `PhoneNumberRecognizer`, `MainlandChinaIdentityCardRecognizer`, `BankCardRecognizer`, and `EmailPaymentAccountRecognizer`. Detection returns match ranges without exposing raw values.
- Builder `addRecognizer(...)` appends to the default recognizer set, while `recognizers(...)` replaces it completely. Streaming buffer length must be derived from the effective recognizer set; an empty set disables detection and uses a zero-length tail buffer.
- The default mask character is `*`. Phone numbers retain the country code plus the first 3 and last 4 national digits; ID cards retain the first 3 and last 4 characters; bank cards retain the first 4 and last 4 digits; email domains remain visible.
- `C2ToolArgumentsMaskingAdvisor` recursively masks JSON string values before tool execution. Numeric JSON values remain unchanged. Malformed JSON containing detectable C2 data must fail closed without logging the original arguments.
- `C2AssistantMessageMaskingAdvisor` masks all assistant generations for non-stream and stream calls. Streaming uses a per-subscription rolling buffer sized to the maximum recognizer match length so sensitive values split across chunks are not released as plaintext.
- Both advisors must be built with the same explicit `C2DataMaskingService` instance. Their default order is inside the standard `ToolCallAdvisor` and chat-memory advisors so tools and persisted memory receive masked content.

---

## 1. Backend Module (spring-ai-harness-mcp-server)

### 1.1 Architecture & Isolation Model

#### Isolation Model Diagram
```
oss://{bucket}/{ossPrefix}/{system}-{agent}-{user}/
       │         │           │        │       │
       │         │           │        │       └── User-level isolation (delimiter: -)
       │         │           │        └────────── Agent-level isolation (delimiter: -)
       │         │           └─────────────────── System-level isolation
       │         └─────────────────────────────── Configurable prefix (default: mcp/workspaces/)
       └───────────────────────────────────────── OSS Bucket
```

From the agent's perspective, `pwd` defaults to `./`. The agent cannot perceive its full physical OSS path `oss://{bucket}/mcp/workspaces/{system}-{agent}-{user}/`.

#### Core Component Responsibilities

| Package | Responsibility | Security Boundary |
| :--- | :--- | :--- |
| `auth/` | Extract and validate identity from Headers, producing `WorkspaceIdentity` | Token sanitization, whitespace trimming, invalid format rejection |
| `tool/` | Define `@McpTool` tools and handle basic parameter validation | Parameter range checks, injection prevention |
| `storage/` | Physical file read/write/trash operations, factory assembly, and streaming content processing via `FileContentProcessor` | Absolute path interception, escape logic prevention |
| `snapshot/` | Version control management, pre-write and rollback snapshots | Snapshot hiding, recovery fallback |
| `autoconfig/` | Assemble the above beans, attach pluggable `ObservationRegistry` wrappers | Lightweight pluggable tracing control |

### 1.2 Required Configuration
```properties
# Alibaba Cloud OSS Connection
aliyun.oss.endpoint=https://oss-cn-hangzhou.aliyuncs.com
aliyun.oss.access-key-id=<your-access-key-id>
aliyun.oss.access-key-secret=<your-access-key-secret>
spring.ai.harness.mcp.server.oss-bucket=<your-bucket-name>

# Observability Tracing (default: false)
spring.ai.harness.mcp.server.observability.enabled=false
# Export type: otlp, none (default: otlp)
spring.ai.harness.mcp.server.observability.export-type=otlp

# Relay streamable-http MCP Server Configuration
spring.ai.harness.mcp.server.relay.enabled=false
spring.ai.harness.mcp.server.relay.url=http://localhost:8081
# Map of static headers (e.g., Authorization)
spring.ai.harness.mcp.server.relay.headers.Authorization=Bearer <downstream-token>

# Temporary Download URL Configuration
spring.ai.harness.mcp.server.download.enabled=true
spring.ai.harness.mcp.server.download.default-ttl=1h
spring.ai.harness.mcp.server.download.max-ttl=8h
spring.ai.harness.mcp.server.download.public-endpoint=

# File Upload Attachment Configuration
spring.ai.harness.mcp.server.attachment.base-path=attachments
spring.ai.harness.mcp.server.attachment.default-conversation-id=default

# Multipart file upload limits
spring.servlet.multipart.max-file-size=50MB
spring.servlet.multipart.max-request-size=100MB

# Snapshot Auto-Clean Configuration (default: auto-clean-enabled is false, clean-ttl is 7d)
spring.ai.harness.mcp.server.snapshot.auto-clean-enabled=false
spring.ai.harness.mcp.server.snapshot.clean-ttl=7d
```

### 1.3 Backend Coding & Quality Standards

#### Coding Style Conventions
- **Logging**: Direct console output via `System.out.println` is strictly forbidden. Use SLF4J interfaces (via Lombok `@Slf4j`) for structured logging (`log.info`, `log.error`).
- **Java 17 Features**: DTOs and read-only metadata models should prefer Java 17 `record` declarations for immutability.
- **Parameter Validation**: Input-layer parameters must use Jakarta Bean Validation (`@NotNull`, `@Size`) for field boundary constraints.
- **Tool Descriptions**: `@McpTool` method descriptions and parameter annotations must be in **English** (for LLM comprehension), while business logic comments in code must be in **Chinese**.
- **Streaming File Processing**: All file content parsing (text, images, PDFs, Office documents) must be performed through `FileContentProcessor` using `InputStream`-based streaming to prevent OOM risks with large files. Storage implementations (`AliyunOssStorage`, etc.) should only handle byte transport, delegating content formatting to `FileContentProcessor`.

#### Unit Testing & Coverage Requirements
- **Full coverage**: Every new business logic or utility class must have a corresponding JUnit 5 test class.
- **Coverage threshold**: Core logic classes (path resolution, permission validation, content processing, etc.) must achieve **80%+** line coverage and branch coverage.
- **Mocking strategy**: All tests involving `AliyunOssStorage` must fully mock the `OSS` client and network requests via Mockito. Connecting to physical Alibaba Cloud OSS is strictly forbidden.

---

## 2. Frontend Module (spring-ai-harness-server-frontend)

### 2.1 Tech Stack & Architecture
- **Core Framework**: React 18 (JavaScript) + Vite build chain.
- **Component System**: Ant Design (`antd` v5) with flat dark glassmorphism theme.
- **File Drag & Drop**: HTML5 native Drag & Drop API for cross-breadcrumb and directory drag-move-rename.
- **Build Scripts**:
  * Dev hot reload: `npm run dev` (port 3000)
  * Production build: `npm run build` (output to `dist/`)

```
spring-ai-harness-server-frontend/
├── index.html                   # Entry HTML with ESM script imports
├── vite.config.js               # Vite config (JSX Esbuild transform & proxy forwarding)
├── src/
│   ├── components/
│   │   ├── FileExplorer.js      # Windows Explorer-style main component
│   │   ├── FileListTable.js     # Table list view component
│   │   ├── FileGridCards.js     # Grid card view component
│   │   ├── McpDebugger.js       # Built-in MCP Client JSON-RPC debug panel
│   │   └── SnapshotDrawer.js    # Sidebar snapshot list & Rewind console
│   ├── services/
│   │   └── api.js               # Axios-based backend service & /mcp dispatcher
│   └── App.js                   # Top-level sticky navbar & view switching
```

### 2.2 Dev Server Reverse Proxy
During local development (`npm run dev`), Vite's dev proxy transparently routes matching traffic to the Spring Boot backend:
- `/api/**` -> `http://localhost:8080/api/**` (Business REST API)
- `/mcp` -> `http://localhost:8080/mcp` (Stateless JSON-RPC endpoint)

### 2.3 Frontend Coding & Design Standards

#### UI & Component Guidelines
- **Ant Design first**: Do not hand-write basic layouts arbitrarily. Prefer and reuse Ant Design v5 layout components (`Space`, `Flex`, `Row`, `Col`, `Table`, `Card`).
- **Style consistency**: Do not introduce TailwindCSS or heavy CSS-in-JS libraries. Use the global dark glassmorphism theme from `styles/index.css` with unified custom Theme Tokens (avoid hardcoded hex colors).
- **Icon usage**: Always use `@ant-design/icons` with per-component imports for tree-shaking optimization by Vite/Rollup.

#### Code Style Conventions
- **Component pattern**: Prefer Functional Components + React Hooks. Use `useMemo` and `useCallback` for complex calculations or child prop passing to avoid unnecessary re-renders.
- **Naming conventions**:
  * Component files: `PascalCase` (e.g., `FileListTable.js`)
  * Custom hooks: `camelCase` starting with `use`
  * Utility/non-component files: `kebab-case`
- **Destructured props**: Component props should be destructured directly in the function signature for readability.

---

## 3. Gateway Module (mcp-tool-gateway)

### 3.1 Tech Stack & Core Mechanisms
- **Core Framework**: Spring Boot 3.5.x, Java 17, and pure Jackson-based JSON-RPC handling (without static registry dependency).
- **Core Features**:
  * Configurable header extraction via `spring.ai.mcp.tool-gateway.forward-headers`.
  * Gateway Authentication via `GatewayAuthProvider` (returns HTTP 401 on auth failure).
  * Header-based permission filtering via `ToolPermissionFilter` to restrict tool access.
  * HTTP Bypass forwarding using `RestClient` with dynamic URL path parameters and header forwarding.

### 3.2 Required Configuration
```properties
# Standalone execution port
server.port=8090

# Gateway Enable/Disable (default: true)
spring.ai.mcp.tool-gateway.enabled=true

# Tool catalog classpath registry file
spring.ai.mcp.tool-gateway.catalog-path=classpath:tool-catalog.json

# Extract and forward headers (comma separated)
spring.ai.mcp.tool-gateway.forward-headers=Authorization,X-Tenant-Id
```

---


## 4. Skills Module (spring-ai-skills)

### 4.1 Purpose & Design
- **Role**: Provide domain skills that agents can execute through Spring AI tool calling, independent of the MCP server process.
- **Current skills**:
  - `xlsx` — create/preview/edit Excel workbooks, evaluate formulas, and convert CSV/TSV to XLSX.
  - `docx` — create/preview/edit Word documents, merge runs, comments, and accept tracked changes (Apache POI).
- **I/O boundary**: Domain tools such as `XlsxTools` operate on **local filesystem paths**. Remote OSS files are materialised via `OssLocalFileTools` (`downloadOssFileToLocal` / `uploadLocalFileToOss`) which talks to the Aliyun OSS SDK directly (`OSS`, `bucketName`, optional `prefix`, `downloadPath` default `/tmp`).
- **Prompt packaging**: Each skill ships a classpath `SKILL.md` under `src/main/resources/skills/<name>/SKILL.md`. Agents discover it via `SkillUtil.loadClassPath("classpath*:skills/**/SKILL.md")` and/or workspace skill directories.

### 4.2 Module Layout
```
spring-ai-skills/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/io/github/springai/harness/skills/
    │   │   ├── storage/
    │   │   │   └── OssLocalFileTools.java     # Aliyun OSS SDK ↔ local downloadPath bridge tools
    │   │   ├── xlsx/
    │   │   │   ├── XlsxTools.java                 # @Tool methods for spreadsheet ops (local FS)
    │   │   │   ├── CellSpec.java / CellStyleSpec.java / SheetSpec.java
    │   │   │   └── FormulaEvaluationResult.java   # JSON result model for formula checks
    │   │   └── docx/
    │   │       ├── DocxTools.java                 # @Tool methods for Word ops
    │   │       └── Docx*Spec.java                 # Block/paragraph/run/table/page specs
    │   └── resources/skills/
    │       ├── xlsx/SKILL.md
    │       └── docx/SKILL.md
    └── test/java/.../xlsx|docx/*ToolsTest.java
```

### 4.3 XLSX Tool Contract
| Tool | Responsibility | Limits / Notes |
| :--- | :--- | :--- |
| `readXlsxPreview` | Markdown preview of sheets + top rows | **local FS path**; default 10 rows/sheet, hard cap 50 |
| `readXlsxSheet` | Paginated cell/formula dump | 1-indexed rows; max 2000 rows per call |
| `createXlsx` | Build workbook from `List<SheetSpec>` | uses SXSSF when estimated cells > 10000 |
| `editXlsxCells` | Patch cells in an existing workbook | preserves untouched cells |
| `evaluateXlsxFormulas` | POI formula evaluation + error summary | optional write-back of **cached results only** (formulas preserved) |
| `convertCsvToXlsx` | CSV/TSV → XLSX | streaming SXSSF writer; 50MB input cap |

### 4.3.0 OSS ↔ Local Bridge (`OssLocalFileTools`)
| Tool | Responsibility | Limits / Notes |
| :--- | :--- | :--- |
| `downloadOssFileToLocal` | Download OSS object `prefix+path` into `{downloadPath}/{uuid}/{fileName}` | constructor/builder: `OSS`, `bucketName`, optional `prefix`, `downloadPath` (default `/tmp`); rejects absolute/`..` paths; 50MB cap |
| `uploadLocalFileToOss` | Upload local file to OSS `prefix+path` | 50MB cap; overwrites destination object |

### 4.3.1 DOCX Tool Contract
| Tool | Responsibility | Limits / Notes |
| :--- | :--- | :--- |
| `readDocxPreview` | Markdown overview + first body blocks | default 20 blocks, hard cap 100 |
| `readDocxContent` | Paginated body dump (paragraphs/tables) | 1-indexed start; max 500 blocks/call |
| `createDocx` | Build `.docx` from `List<DocxBlockSpec>` | optional A4 page setup; tables use DXA widths |
| `replaceDocxText` | Find/replace in body + table cells | local FS paths; prefer `mergeDocxRuns` first when text is split |
| `mergeDocxRuns` | Coalesce adjacent same-format runs | optional `outputPath`; 50MB cap |
| `addDocxComment` | Add comment (optional `anchorText`) | POI high-level; extended comment parts not guaranteed |
| `acceptDocxTrackedChanges` | Accept `w:ins` / drop `w:del` | pure Java CT-level; optional `outputPath` |

### 4.4 Coding Standards Specific to Skills
- Keep `@Tool` / `@ToolParam` descriptions in **English** and synchronized with `SKILL.md` (limits, output field names, supported formulas).
- Prefer returning structured error strings/JSON for tool failures when practical; document any thrown validation exceptions.
- Never hardcode FQCNs; import types explicitly (including POI XSSF types).
- Unit tests must use temporary `LocalFileStorage` directories and must not touch real cloud storage.
- New skills should follow the same package pattern: `skills/<name>` resources + `io.github.springai.harness.skills.<name>` tools + dedicated tests.

---

## 5. Global Build & Test Commands

### Build & Test Backend Modules
```bash
# Compile backend modules
./mvnw compile -pl spring-ai-harness-mcp-server
./mvnw compile -pl spring-ai-skills
./mvnw compile -pl mcp-tool-gateway

# Run backend unit tests
./mvnw test -pl spring-ai-harness-mcp-server
./mvnw test -pl spring-ai-skills
./mvnw test -pl mcp-tool-gateway
```

### Build & Run Frontend
```bash
# Enter frontend directory
cd spring-ai-harness-server-frontend

# Install dependencies
npm install

# Offline fetch Windows-native esbuild/rollup deps (only when packaging offline bundles for Windows from Mac)
npm install --os=win32 --cpu=x64

# Dev hot reload
npm run dev

# Production build
npm run build
```

---

## 6. AI Assistant Development Guidelines & Quality Guardrails

1. **Safety first (Surgical Edits)**: When modifying existing components or code logic, make minimal "surgical" changes. Preserve existing formatting and indentation. Never introduce file system paths that escape workspace isolation boundaries.
2. **Test alongside development**: After adding any new method or logic fix, immediately run the full unit test suite (`./mvnw test`) to confirm no regression has occurred.
3. **Configuration file guardrails**: Never modify public Maven or npm repository source URLs in `pom.xml`, `package.json`, or local build configurations without explicit approval.
