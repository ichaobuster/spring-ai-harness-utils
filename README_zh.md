# Spring AI Harness Utils

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java Version](https://img.shields.io/badge/Java-17%2B-orange)](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)

[English](README.md)

一个基于 [Spring AI](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/index.html) 的工具库，为 AI 应用提供受 [Claude Code](https://code.claude.com/docs/en/settings#tools-available-to-claude) 启发的 harness 工具与 agent 技能管理能力。

## 概述

`spring-ai-harness-utils` 提供了一个统一的、安全的 **MCP (Model Context Protocol) Server**，用于替代 agent 内置的文件系统工具，实现工作区隔离、操作可审计以及版本回滚能力。通过禁用 agent 原生的文件操作并将其路由到本 MCP Server，实现 **一次开发，所有 agent 通用** 的安全文件访问方案。

### 核心特性

- **🔒 工作区隔离**：每个 agent 在阿里云 OSS 上的 `{system}-{agent}-{user}` 前缀内严格隔离运行。绝对路径（以 `/` 开头）会被直接拒绝并抛出 `SecurityException`。
- **📁 完整文件工具集**：`Read`、`Write`、`Edit`、`Glob`、`Grep`、`ListDirectory`、`Trash` —— agent 所需的全部文件操作能力，通过 MCP 协议统一交付。
- **📸 快照与回滚**：自动前置操作快照，支持一键 `Rewind` 回滚。双重安全兜底机制确保可以撤销已执行的撤销操作。
- **🖼️ 多媒体读取**：图片（PNG/JPG/JPEG）自动按比例缩放后以 MCP `ImageContent` Base64 格式返回；PDF 支持页码范围提取；Office 文档（DOCX/XLSX/PPTX）解析为纯文本。
- **🔄 流式处理**：所有文件内容处理均通过 `FileContentProcessor` 以 `InputStream` 流式传输方式执行，有效防止处理大文件时的内存溢出（OOM）风险。
- **🎯 Agent 技能系统**：支持工作区级 `skills/` 目录的 `SKILL.md` 自动发现，同时通过 MCP Tools 和 `skill://` URI Resources 双协议暴露。
- **📊 可插拔可观测性**：可选的 OpenTelemetry 链路追踪，采用零运行开销的装饰器模式 —— 关闭时无任何性能损耗。
- **🌐 Web 管理控制台**：基于 React 18 + Ant Design 5 构建，提供 Windows 资源管理器风格的文件管理、拖拽操作及 MCP Client JSON-RPC 调试器。

## 模块结构

| 模块 | 说明 |
|------|------|
| `spring-ai-harness-mcp-server` | 核心 MCP Server，包含文件工具、技能系统、快照回滚及流式多媒体支持 |
| `spring-ai-harness-server-frontend` | 基于 React 的 Web 管理控制台 |
| `spring-ai-harness-utils` | 上下文压缩（Context Compact）Advisor，用于 agent 循环上下文管理 |
| `spring-ai-harness-utils-bom` | BOM（Bill of Materials）版本统一管理 |

## 架构概览

```
Agents (openclaw / hermes / qwenpaw / ...)
    │  Authorization: {system}-{agent}-{user}
    │  POST /mcp (Stateless HTTP)
    ▼
spring-ai-harness-mcp-server
    ├── FileSystemTools (@McpTool) ──► StorageProvider (接口)
    │       Read / Write / Edit / ...       └── AliyunOssStorage (实现)
    ├── SkillTools (@McpTool)                       │
    │       ListSkills / ReadSkill          FileContentProcessor
    └── 快照 & 回滚                          (InputStream 流式内容处理)
                                                    │
                                              阿里云 OSS
```

## 快速开始

### 前置条件

- Java 17+
- Maven 3.8+
- 阿里云 OSS Bucket

### 配置

```properties
# 阿里云 OSS 连接
aliyun.oss.endpoint=https://oss-cn-hangzhou.aliyuncs.com
aliyun.oss.access-key-id=<你的密钥ID>
aliyun.oss.access-key-secret=<你的密钥Secret>
spring.ai.harness.mcp.server.oss-bucket=<你的Bucket名称>

# 可选：可观测性配置
spring.ai.harness.mcp.server.observability.enabled=false
spring.ai.harness.mcp.server.observability.export-type=otlp
```

### 编译与运行

```bash
# 编译后端
./mvnw compile -pl spring-ai-harness-mcp-server

# 运行后端单元测试
./mvnw test -pl spring-ai-harness-mcp-server

# 启动前端开发服务器
cd spring-ai-harness-server-frontend
npm install
npm run dev
```

## MCP 工具参考

| 工具 | 说明 |
|------|------|
| `Read` | 读取文件并带行号输出。图片返回 Base64 编码的 `ImageContent`；PDF/Office 文档返回提取的文本内容。PDF 支持 `startPage`/`endPage` 页码范围。 |
| `Write` | 创建或覆写文件（自动生成前置快照） |
| `Edit` | 精确字符串替换，支持单次/全部替换模式（自动生成前置快照） |
| `Glob` | Glob 模式文件搜索（最多返回 100 个结果） |
| `Grep` | 正则表达式搜索，支持上下文行与分页 |
| `ListDirectory` | 列出文件和子目录及元数据信息 |
| `Trash` | 安全软删除至 `.trash/`（自动生成前置快照） |
| `ListSnapshots` | 查询快照历史，支持按路径过滤 |
| `Rewind` | 恢复文件到指定快照状态 |
| `ListSkills` | 发现工作区内可用的技能 |
| `ReadSkill` | 读取技能指令内容 |

## 许可证

本项目采用 [Apache License 2.0](LICENSE.txt) 许可证。
