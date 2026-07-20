# MCP Tool Gateway — AGENTS.md

> [!IMPORTANT]
> **This project is under active development.** This document is a **Living Document** and must be kept in sync with code and configuration changes.

---

## Project Overview

`mcp-tool-gateway` is a lightweight, stateless Model Context Protocol (MCP) gateway server. It provides dynamic tool discovery and secure HTTP bypass forwarding to external API endpoints.

Unlike typical static MCP servers, this gateway:
1. **Validates request credentials** using a pluggable authentication provider (`GatewayAuthProvider`).
2. **Performs header-based permission filtering** on the list of exposed tools.
3. **Bypasses execution directly to external HTTP APIs** while forwarding specified request headers.

---

## Architecture Design

### Gateway Flow Diagram

```
┌─────────────────────────┐
│       MCP Client        │
└────────────┬────────────┘
             │ HTTP POST /mcp (JSON-RPC)
             │ Header: Authorization, etc.
             ▼
┌─────────────────────────────────────────────────────────────────┐
│                      mcp-tool-gateway                           │
│                                                                 │
│ 1. Extract Configured Headers (spring.ai.mcp.tool-gateway.      │
│    forward-headers)                                             │
│ 2. Authenticate Request via GatewayAuthProvider                 │
│    (Default: AllowAllGatewayAuthProvider, returns 401 if fails) │
│                                                                 │
│ 3. Dispatch JSON-RPC Method:                                    │
│    ├── initialize -> Return capabilities & server metadata      │
│    ├── tools/list -> Filter tools via ToolPermissionFilter      │
│    └── tools/call -> Forward request via ToolInvocationService   │
└──────────────────────┬──────────────────────────────────────────┘
                       │ HTTP (GET/POST/etc.)
                       ▼
             ┌──────────────────┐
             │   External API   │
             │  (Downstream)    │
             └──────────────────┘
```

---

## Module Structure

```
mcp-tool-gateway/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/io/github/springai/harness/toolgateway/
    │   │   ├── McpToolGatewayApplication.java        # Main Spring Boot application entry
    │   │   ├── auth/
    │   │   │   ├── GatewayAuthProvider.java           # Authentication interface
    │   │   │   ├── GatewayAuthenticationException.java # Auth failure exception
    │   │   │   └── AllowAllGatewayAuthProvider.java   # Default no-op auth provider
    │   │   ├── autoconfig/
    │   │   │   ├── ToolGatewayAutoConfiguration.java  # Auto configuration class
    │   │   │   └── ToolGatewayProperties.java         # Configuration properties
    │   │   ├── catalog/
    │   │   │   ├── HttpEndpointConfig.java            # Tool downstream HTTP endpoint configuration
    │   │   │   ├── ToolAnnotations.java               # MCP Tool Annotations DTO
    │   │   │   ├── ToolCatalogProvider.java           # Tool catalog source interface
    │   │   │   ├── ToolDefinition.java                # Tool definition DTO
    │   │   │   └── JsonResourceToolCatalogProvider.java # classpath:tool-catalog.json provider
    │   │   ├── controller/
    │   │   │   └── ToolGatewayMcpController.java      # JSON-RPC REST Controller
    │   │   ├── filter/
    │   │   │   ├── ToolPermissionFilter.java          # Permission filter interface
    │   │   │   └── AllowAllToolPermissionFilter.java  # Default no-op permission filter
    │   │   └── service/
    │   │       ├── ToolCatalogService.java            # Tool resolution and catalog management
    │   │       └── ToolInvocationService.java         # HTTP bypass request execution service
    │   └── resources/
    │       ├── META-INF/spring/
    │       │   └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
    │       ├── application.properties                 # Default application properties
    │       └── tool-catalog.json                      # Predefined tool definition registry
    └── test/
        └── java/io/github/springai/harness/toolgateway/
            ├── autoconfig/
            │   └── ToolGatewayAutoConfigurationTest.java
            ├── catalog/
            │   └── JsonResourceToolCatalogProviderTest.java
            ├── controller/
            │   └── ToolGatewayMcpControllerTest.java
            ├── filter/
            │   └── AllowAllToolPermissionFilterTest.java
            └── service/
                ├── ToolCatalogServiceTest.java
                └── ToolInvocationServiceTest.java
```

---

## Core Component Details

### 1. ToolGatewayMcpController
Handles incoming POST requests to the MCP endpoint (default `/mcp`). 
- Extracts specified request headers defined in `spring.ai.mcp.tool-gateway.forward-headers`.
- Runs `GatewayAuthProvider.authenticate(headers)`. Returns HTTP 401 on authentication failure.
- Dispatches MCP methods: `initialize`, `tools/list` (filtered), `tools/call` (invoked via HTTP), and `notifications/initialized`.

### 2. GatewayAuthProvider
Authenticates the extracted header map.
- **`AllowAllGatewayAuthProvider`** is configured by default.
- Custom authentication beans can replace this default implementation for database token verification or OAuth validation.

### 3. ToolPermissionFilter
Filters out unauthorized tools from the catalog.
- **`AllowAllToolPermissionFilter`** is configured by default.
- Filters can examine headers (e.g. `Authorization` system-agent-user formats) to restrict access to sensitive tools (like administrative database executions).

### 4. ToolInvocationService
Performs downstream REST calls using Spring's `RestClient`.
- Resolves URL template parameters dynamically using tool call argument values.
- Forwards extracted request headers if the tool has `forwardAuthHeader: true`.
- Converts HTTP exceptions and statuses into standard MCP `isError: true` responses.

---

## Configuration Reference

```properties
# Port to run the standalone gateway
server.port=8090

# Enable/Disable the gateway (default: true)
spring.ai.mcp.tool-gateway.enabled=true

# Tool Catalog definition location
spring.ai.mcp.tool-gateway.catalog-path=classpath:tool-catalog.json

# MCP HTTP Endpoint
spring.ai.mcp.tool-gateway.mcp-endpoint=/mcp

# Server Info
spring.ai.mcp.tool-gateway.server-name=mcp-tool-gateway
spring.ai.mcp.tool-gateway.server-version=1.0.0

# Request headers to extract and pass/forward (comma separated)
spring.ai.mcp.tool-gateway.forward-headers=Authorization,X-Custom-Header
```

---

## Development Standards

- **Java 17+**
- **Spring Boot 3.5.x**
- **Lombok**
- Direct SLF4J logging (`@Slf4j`), no `System.out.println`.
- Maintain test coverage when updating components.

---

## Testing

```bash
# Compile and run gateway unit tests
./mvnw test -pl mcp-tool-gateway
```
