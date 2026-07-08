# Spring AI Harness Utils

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java Version](https://img.shields.io/badge/Java-17%2B-orange)](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)

[中文文档](README_zh.md)

A [Spring AI](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/index.html) library that brings [Claude Code](https://code.claude.com/docs/en/settings#tools-available-to-claude)-inspired harness tools and agent skills to your AI applications.

## Overview

`spring-ai-harness-utils` provides a unified, secure **MCP (Model Context Protocol) Server** that replaces agents' built-in file system tools with workspace-isolated, auditable, and rollback-capable alternatives. By disabling agents' native file operations and routing them through this MCP Server, you get **develop once, secure all agents**.

### Key Features

- **🔒 Workspace Isolation**: Every agent operates within a strictly isolated `{system}-{agent}-{user}` workspace prefix on Alibaba Cloud OSS. Absolute paths are rejected with `SecurityException`.
- **📁 Full File System Toolkit**: `Read`, `Write`, `Edit`, `Glob`, `Grep`, `ListDirectory`, `Trash` — all the tools agents need, delivered via MCP protocol.
- **📸 Snapshot & Rewind**: Automatic pre-operation snapshots with one-click `Rewind` rollback. Double-fallback safety ensures you can undo an undo.
- **🖼️ Multimedia Reading**: Images (PNG/JPG/JPEG) are auto-resized and returned as MCP `ImageContent` with Base64 encoding. PDFs support page-range extraction. Office documents (DOCX/XLSX/PPTX) are parsed to text.
- **🔄 Streaming Processing**: All file content processing uses `InputStream`-based streaming via `FileContentProcessor` to prevent OOM errors with large files.
- **🎯 Agent Skills**: Workspace-scoped `skills/` directory with `SKILL.md` discovery, exposed via both MCP Tools and `skill://` URI Resources.
- **📊 Pluggable Observability**: Optional OpenTelemetry tracing with zero-overhead decorator pattern — no runtime cost when disabled.
- **🌐 Web Management Console**: React 18 + Ant Design 5 frontend with Windows Explorer-style file management, drag-and-drop, and an MCP Client JSON-RPC debugger.

## Modules

| Module | Description |
|--------|-------------|
| `spring-ai-harness-mcp-server` | Core MCP Server with file tools, skills, snapshots, and streaming multimedia support |
| `spring-ai-harness-server-frontend` | React-based web management console |
| `spring-ai-harness-utils` | Context Compact advisors for agent loop context management |
| `spring-ai-harness-utils-bom` | Bill of Materials for version management |

## Architecture

```
Agents (openclaw / hermes / qwenpaw / ...)
    │  Authorization: {system}-{agent}-{user}
    │  POST /mcp (Stateless HTTP)
    ▼
spring-ai-harness-mcp-server
    ├── FileSystemTools (@McpTool) ──► StorageProvider (interface)
    │       Read / Write / Edit / ...       └── AliyunOssStorage (impl)
    ├── SkillTools (@McpTool)                       │
    │       ListSkills / ReadSkill          FileContentProcessor
    └── Snapshot & Rewind                   (streaming InputStream processing)
                                                    │
                                            Alibaba Cloud OSS
```

## Quick Start

### Prerequisites

- Java 17+
- Maven 3.8+
- Alibaba Cloud OSS bucket

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
# Build backend
./mvnw compile -pl spring-ai-harness-mcp-server

# Run backend tests
./mvnw test -pl spring-ai-harness-mcp-server

# Frontend dev server
cd spring-ai-harness-server-frontend
npm install
npm run dev
```

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
