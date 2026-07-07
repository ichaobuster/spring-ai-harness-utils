# Spring AI Harness MCP Server

Spring AI Harness MCP Server is a Model Context Protocol (MCP) server designed to expose secure, workspace-isolated file system operations and version snapshot capabilities to AI agents.

## Key Features

- **Protocol Parity**: Fully implements the Model Context Protocol (MCP) using Spring AI, providing tools (`Read`, `Write`, `Edit`, `Glob`, `Grep`, `ListDirectory`, `Trash`, `ListSnapshots`, `Rewind`) and resources (`skill://list`, `skill://{skillName}`).
- **Strict Isolation**: Uses formatted `Authorization: {system}-{agent}-{user}` headers to construct separate prefixes in Aliyun OSS, ensuring complete data isolation between different sessions/users.
- **Safety Snapshots**: Automatically creates pre-operation snapshots before modifying files, supporting instant rollbacks and double-backup rewinds.
- **Pluggable Observability**: Built-in OpenTelemetry (OTel) and Micrometer Tracing integration using Decorator patterns, allowing request tracing with zero overhead when disabled.

## Requirements

- Java 17 or higher
- Maven 3.6+ (or use the packaged wrapper `./mvnw`)

## Configuration

Configure your application properties in `src/main/resources/application.properties` or set them as environment variables:

### Aliyun OSS (Required)
```properties
aliyun.oss.endpoint=https://oss-cn-hangzhou.aliyuncs.com
aliyun.oss.access-key-id=YOUR_ACCESS_KEY_ID
aliyun.oss.access-key-secret=YOUR_ACCESS_KEY_SECRET
spring.ai.harness.mcp.server.oss-bucket=YOUR_OSS_BUCKET_NAME
```

### Optional Configurations
```properties
# Workspace prefix inside the bucket (default: mcp/workspaces/)
spring.ai.harness.mcp.server.oss-prefix=mcp/workspaces/

# Admin API authorization token (default: admin-secret)
spring.ai.harness.mcp.server.admin-token=admin-secret

# Enable OpenTelemetry tracing (default: false)
spring.ai.harness.mcp.server.observability.enabled=false
# Exporter type: "otlp", "stdout", "none" (default: stdout)
spring.ai.harness.mcp.server.observability.export-type=stdout
# Tracing sampling probability (default: 1.0)
spring.ai.harness.mcp.server.observability.probability=1.0
```

## How to Build & Run

### Build the module
```bash
./mvnw clean package -pl spring-ai-harness-mcp-server -DskipTests
```

### Run the application
```bash
./mvnw spring-boot:run -pl spring-ai-harness-mcp-server
```
By default, the server will start on port `8080` (or the port defined in application properties) with the stateless MCP HTTP transport listening at `http://localhost:8080/mcp`.

## Unit Testing
Verify the server components using:
```bash
./mvnw test -pl spring-ai-harness-mcp-server
```
No live OSS connection is required; all tests run cleanly using Mockito.
