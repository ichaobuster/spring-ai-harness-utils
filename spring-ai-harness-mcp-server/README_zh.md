# Spring AI Harness MCP Server (中文文档)

`spring-ai-harness-mcp-server` 是一个基于 Model Context Protocol (MCP) 的服务端，旨在向 AI Agent 提供安全、工作区隔离的文件系统操作以及版本快照/回滚能力。

[English](README.md)

## 核心特性

- **协议对齐**：使用 Spring AI 完整实现了 Model Context Protocol (MCP) 规范，提供工具（`Read`、`Write`、`Edit`、`Glob`、`Grep`、`ListDirectory`、`Trash`、`ListSnapshots`、`Rewind`）与资源（`skill://list`、`skill://{skillName}`）。
- **严格的工作区隔离**：利用请求头中的 `Authorization: {system}-{agent}-{user}` 来构建阿里云 OSS 中的独立路径前缀，确保不同会话/用户之间的数据彻底隔离。绝对路径操作将被直接拦截拒绝。
- **流式处理与防 OOM**：所有文件读写和解析逻辑（纯文本、图片、PDF、Office 文档）均通过 `FileContentProcessor` 基于 `InputStream` 流式进行处理，规避了大文件载入内存时的 OOM 风险。
- **多媒体文件支持**：
  - **图片**：支持 PNG、JPG、JPEG 的等比例缩放（如果单边尺寸超过限制 `MAX_IMAGE_EDGE`，默认 2048 像素），并以 MCP 标准 Base64 `ImageContent` 回传给大模型。
  - **PDF**：使用 `PagePdfDocumentReader` 流式提取，并支持 1-based 页码范围切片读取。
  - **Office 文档**：使用 `TikaDocumentReader` 流式读取并合并提取纯文本。
- **安全快照机制**：在执行写文件（`Write`）、编辑（`Edit`）或软删除（`Trash`）等修改操作前，自动生成前置快照，支持一键 `Rewind` 恢复，并在恢复时提供双重安全备份。
- **可插拔可观测性**：基于装饰器模式集成了 OpenTelemetry (OTel) 和 Micrometer 链路追踪，在未启用时可保证零开销运行。

## 运行环境

- Java 17 或更高版本
- Maven 3.6+（或者直接使用内置的 `./mvnw`）

## 配置说明

在 `src/main/resources/application.properties` 中配置应用属性，或者通过环境变量进行设置：

### 阿里云 OSS 配置 (必填)
```properties
aliyun.oss.endpoint=https://oss-cn-hangzhou.aliyuncs.com
aliyun.oss.access-key-id=你的ACCESS_KEY_ID
aliyun.oss.access-key-secret=你的ACCESS_KEY_SECRET
spring.ai.harness.mcp.server.oss-bucket=你的OSS_BUCKET名称
```

### 可选配置
```properties
# Bucket 中的工作区路径前缀 (默认值: mcp/workspaces/)
spring.ai.harness.mcp.server.oss-prefix=mcp/workspaces/

# 管理员 API 授权令牌 (默认值: admin-secret)
spring.ai.harness.mcp.server.admin-token=admin-secret

# 是否启用 OpenTelemetry 链路追踪 (默认值: false)
spring.ai.harness.mcp.server.observability.enabled=false
# 可观测性数据导出方式: "otlp", "none" (默认值: otlp)
spring.ai.harness.mcp.server.observability.export-type=otlp
# 链路追踪采样率 (默认值: 1.0)
spring.ai.harness.mcp.server.observability.probability=1.0
```

## 编译与运行

### 编译模块
```bash
./mvnw clean package -pl spring-ai-harness-mcp-server -DskipTests
```

### 启动服务
```bash
./mvnw spring-boot:run -pl spring-ai-harness-mcp-server
```
默认情况下，服务端会在 `8080` 端口（或 application.properties 中配置的端口）启动，无状态 MCP HTTP 传输监听端点为 `http://localhost:8080/mcp`。

## 单元测试
运行以下命令来执行该模块的单元测试：
```bash
./mvnw test -pl spring-ai-harness-mcp-server
```
不需要物理连接阿里云 OSS，所有的测试都通过 Mockito 进行了全量 Mock，可独立在本地或 CI/CD 环境中通过。
