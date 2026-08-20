# Spring AI Harness Utils

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java Version](https://img.shields.io/badge/Java-17%2B-orange)](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)

[中文文档](README_zh.md)

A [Spring AI](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/index.html) library that brings [Claude Code](https://code.claude.com/docs/en/settings#tools-available-to-claude)-inspired harness tools and agent skills to your AI applications.

## Overview

`spring-ai-harness-utils` provides a unified, secure **MCP (Model Context Protocol) Server** that replaces agents' built-in file system tools with workspace-isolated, auditable, and rollback-capable alternatives. By disabling agents' native file operations and routing them through this MCP Server, you get **develop once, secure all agents**.

Alongside the MCP server, the project ships reusable Spring AI building blocks: context-compact advisors, C2 sensitive-data masking, storage-backed tools, classpath/workspace skill loading, an HTTP MCP tool gateway, and executable domain skills such as spreadsheet tooling.

### Key Features

- **🔒 Workspace Isolation**: Every agent operates within a strictly isolated `{system}-{agent}-{user}` workspace prefix on Alibaba Cloud OSS. Absolute paths are rejected with `SecurityException`.
- **📁 Full File System Toolkit**: `Read`, `Write`, `Edit`, `Glob`, `Grep`, `ListDirectory`, `Trash` — all the tools agents need, delivered via MCP protocol.
- **📸 Snapshot & Rewind**: Automatic pre-operation snapshots with one-click `Rewind` rollback. Double-fallback safety ensures you can undo an undo.
- **🖼️ Multimedia Reading**: Images (PNG/JPG/JPEG) are auto-resized and returned as MCP `ImageContent` with Base64 encoding. PDFs support page-range extraction. Office documents (DOCX/XLSX/PPTX) are parsed to text.
- **🔄 Streaming Processing**: All file content processing uses `InputStream`-based streaming via `FileContentProcessor` to prevent OOM errors with large files.
- **🎯 Agent Skills**: Workspace-scoped `skills/` directory with `SKILL.md` discovery, exposed via both MCP Tools and `skill://` URI Resources. Classpath skills can also be loaded through `SkillUtil`.
- **📊 Document Skills (`spring-ai-skills`)**: Apache POI-based Spring AI `@Tool` implementations for XLSX (create/preview/edit/formulas/CSV) and DOCX (create/preview/replace/comments/accept revisions) through `StorageProvider`.
- **🚪 MCP Tool Gateway**: Stateless gateway that authenticates requests, filters tool catalogs by headers, and transparently forwards tool calls to downstream HTTP APIs.
- **📈 Pluggable Observability**: Optional OpenTelemetry tracing with zero-overhead decorator pattern — no runtime cost when disabled.
- **🛡️ C2 Data Masking**: Detect and mask phone numbers, mainland China ID cards, Luhn-valid bank cards, and email payment accounts in tool arguments and assistant messages, including chunk-safe streaming responses.
- **🌐 Web Management Console**: React 18 + Ant Design 5 frontend with Windows Explorer-style file management, drag-and-drop, and an MCP Client JSON-RPC debugger.

## Modules

| Module | Description |
|--------|-------------|
| `spring-ai-harness-mcp-server` | Core MCP Server with file tools, skills, snapshots, and streaming multimedia support |
| `spring-ai-harness-server-frontend` | React-based web management console |
| `spring-ai-harness-utils` | Storage providers, harness tools, skills helpers, context-compact advisors, and C2 data masking |
| `spring-ai-skills` | Executable agent skills implemented as Spring AI Tools (XLSX / DOCX) |
| `mcp-tool-gateway` | Stateless MCP gateway with auth, permission filtering, and HTTP tool bypass |
| `spring-ai-harness-utils-bom` | Bill of Materials for version management |

## Architecture

```
Agents (openclaw / hermes / qwenpaw / ...)
    │  Authorization: {system}-{agent}-{user}
    │  POST /mcp (Stateless HTTP)
    ▼
spring-ai-harness-mcp-server
    ├── FileSystemTools (@McpTool) ──► StorageProvider (interface)
    │       Read / Write / Edit / ...       ├── AliyunOssStorage (impl)
    ├── SkillTools (@McpTool)               └── LocalFileStorage (impl)
    │       ListSkills / ReadSkill
    └── Snapshot & Rewind              FileContentProcessor
                                              (streaming InputStream processing)
                                                     │
                                             Alibaba Cloud OSS

Optional sidecars / libraries:
  spring-ai-skills   → XlsxTools / DocxTools (+ classpath skills/{xlsx,docx}/SKILL.md)
  mcp-tool-gateway   → header auth + tools/list filter + HTTP bypass tools/call
```

## Quick Start

### Prerequisites

- Java 17+
- Maven 3.8+
- Alibaba Cloud OSS bucket (for the MCP server module)

### Configuration

```properties
# Alibaba Cloud OSS
aliyun.oss.endpoint=https://oss-cn-hangzhou.aliyuncs.com
aliyun.oss.access-key-id=<your-key-id>
aliyun.oss.access-key-secret=<your-key-secret>
spring.ai.harness.mcp.server.oss-bucket=<your-bucket>

# Optional: Observability
spring.ai.harness.mcp.server.observability.enabled=false
spring.ai.harness.mcp.server.observability.export-type=otlp
```

### Build & Run

```bash
# Build backend modules
./mvnw compile -pl spring-ai-harness-mcp-server
./mvnw compile -pl spring-ai-skills
./mvnw compile -pl mcp-tool-gateway

# Run backend unit tests
./mvnw test -pl spring-ai-harness-mcp-server
./mvnw test -pl spring-ai-skills
./mvnw test -pl mcp-tool-gateway

# Frontend dev server
cd spring-ai-harness-server-frontend
npm install
npm run dev
```

## C2 Sensitive-Data Masking

Create one masking service and pass it to both advisors. The default mask character is `*`; phone numbers retain the country code plus the first 3 and last 4 national digits, ID cards retain the first 3 and last 4 characters, bank cards retain the first and last 4 digits, and email domains remain visible.

```java
C2DataMaskingService maskingService = C2DataMaskingService.builder()
    .maskCharacter('*')
    .defaultPhoneRegion("CN")
    .build();

ChatClient chatClient = ChatClient.builder(chatModel)
    .defaultAdvisors(
        ToolCallAdvisor.builder()
            .toolCallingManager(toolCallingManager)
            .build(),
        C2ToolArgumentsMaskingAdvisor.builder(maskingService).build(),
        C2AssistantMessageMaskingAdvisor.builder(maskingService).build())
    .build();
```

`C2ToolArgumentsMaskingAdvisor` recursively masks JSON string values before a tool runs; JSON numbers remain unchanged. If malformed tool JSON contains detectable C2 data, the call fails closed without including the original arguments in the exception message. `C2AssistantMessageMaskingAdvisor` supports both regular and streaming calls and buffers a bounded tail so values split across chunks are masked before release.

The default builder installs `PhoneNumberRecognizer`, `MainlandChinaIdentityCardRecognizer`, `BankCardRecognizer`, and `EmailPaymentAccountRecognizer`. Add a custom recognizer while retaining these defaults with `addRecognizer(...)`, or replace the complete set for selective detection:

```java
C2DataMaskingService phoneOnly = C2DataMaskingService.builder()
    .recognizers(List.of(new PhoneNumberRecognizer("CN")))
    .build();

C2DataMaskingService extended = C2DataMaskingService.builder()
    .addRecognizer(customRecognizer)
    .build();
```

The streaming tail size is calculated from the effective recognizers. Supplying `recognizers(List.of())` disables detection and buffering.

## Using `spring-ai-skills` (XLSX / DOCX)

Add the dependency (preferably via the BOM):

```xml
<dependency>
  <groupId>io.github.spring-ai.harness</groupId>
  <artifactId>spring-ai-skills</artifactId>
</dependency>
```

Register tools and optionally load the bundled skill prompt:

```java
StorageProvider storage = LocalFileStorage.builder()
    .baseDir(Path.of("/path/to/workspace"))
    .build();

XlsxTools xlsxTools = new XlsxTools(); // local filesystem paths

OssLocalFileTools bridge = OssLocalFileTools.builder()
    .ossClient(ossClient)
    .bucketName("your-bucket")
    .prefix("mcp/workspaces/sys-agent-user/") // optional
    .downloadPath(Path.of("/tmp"))            // optional
    .build();
// bridge.downloadOssFileToLocal("reports/a.xlsx") -> /tmp/{uuid}/a.xlsx

// Expose as Spring AI tools on your ChatClient / ToolCallback provider
// ToolCallbacks.from(xlsxTools)

DocxTools docxTools = new DocxTools();
// ToolCallbacks.from(docxTools)
// Local filesystem paths; use OssLocalFileTools for remote files
// Load classpath SKILL.md for SkillsTool / system prompt injection
List<SkillsTool.Skill> skills = SkillUtil.loadClassPath("classpath*:skills/**/SKILL.md");
```

### XLSX tool surface

| Tool | Description |
|------|-------------|
| `readXlsxPreview` | Markdown preview of sheet names, dimensions, and top rows |
| `readXlsxSheet` | Paginated read of cell values or raw formulas |
| `createXlsx` | Create a workbook from `SheetSpec` / `CellSpec` JSON |
| `editXlsxCells` | Patch specific cells without wiping untouched content |
| `evaluateXlsxFormulas` | Recalculate formulas with POI and report error groups |
| `convertCsvToXlsx` | Stream CSV/TSV into `.xlsx` via SXSSF |

### DOCX tool surface

| Tool | Description |
|------|-------------|
| `readDocxPreview` | Markdown overview of paragraphs/tables and first body blocks |
| `readDocxContent` | Paginated body dump (paragraphs + markdown tables) |
| `createDocx` | Create a `.docx` from ordered block specs (paragraph/table/pageBreak) |
| `replaceDocxText` | Find/replace plain text in body and tables |
| `mergeDocxRuns` | Merge adjacent identically formatted runs |
| `addDocxComment` | Add a comment, optionally anchored to text |
| `acceptDocxTrackedChanges` | Accept insertions and drop deletions |

XLSX/DOCX tool paths are local filesystem paths (absolute or relative). Use `OssLocalFileTools` when files live in remote storage.

## MCP Tools Reference

| Tool | Description |
|------|-------------|
| `Read` | Read files with line-numbered output. Images return Base64 `ImageContent`; PDFs/Office docs return extracted text. Supports `startPage`/`endPage` for PDFs. |
| `Write` | Create or overwrite files (auto pre-snapshot) |
| `Edit` | Precise string replacement with single/replace-all modes (auto pre-snapshot) |
| `Glob` | Glob pattern file search (max 100 results) |
| `Grep` | Regex search with context lines and pagination |
| `ListDirectory` | List files and subdirectories with metadata |
| `Trash` | Safe soft-delete to `.trash/` (auto pre-snapshot) |
| `ListSnapshots` | Query snapshot history, filterable by path |
| `Rewind` | Restore file to a specific snapshot |
| `ListSkills` | Discover workspace skills |
| `ReadSkill` | Read skill instructions |

## License

This project is licensed under the [Apache License 2.0](LICENSE.txt).
