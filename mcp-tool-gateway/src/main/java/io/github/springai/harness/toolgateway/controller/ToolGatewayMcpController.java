package io.github.springai.harness.toolgateway.controller;

import io.github.springai.harness.toolgateway.auth.GatewayAuthProvider;
import io.github.springai.harness.toolgateway.auth.GatewayAuthenticationException;
import io.github.springai.harness.toolgateway.autoconfig.ToolGatewayProperties;
import io.github.springai.harness.toolgateway.service.ToolCatalogService;
import io.github.springai.harness.toolgateway.service.ToolInvocationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP JSON-RPC 请求分发 Controller。
 * 手动解析 JSON-RPC 消息，支持 initialize、tools/list 以及 tools/call。
 * 所有请求需先通过 {@link GatewayAuthProvider} 认证。
 *
 * @author ichaobuster
 */
@RestController
@Slf4j
public class ToolGatewayMcpController {

	private final ToolCatalogService catalogService;
	private final ToolInvocationService invocationService;
	private final ToolGatewayProperties properties;
	private final GatewayAuthProvider authProvider;

	public ToolGatewayMcpController(ToolCatalogService catalogService,
									ToolInvocationService invocationService,
									ToolGatewayProperties properties,
									GatewayAuthProvider authProvider) {
		this.catalogService = catalogService;
		this.invocationService = invocationService;
		this.properties = properties;
		this.authProvider = authProvider;
	}

	@PostMapping(value = "${spring.ai.mcp.tool-gateway.mcp-endpoint:/mcp}", produces = "application/json")
	public ResponseEntity<Map<String, Object>> handleMcpRequest(
			@RequestBody Map<String, Object> requestBody,
			HttpServletRequest httpRequest) {

		String method = (String) requestBody.get("method");
		Object id = requestBody.get("id");

		// 1. 提取配置的转发 Headers
		Map<String, String> forwardedHeaders = extractForwardHeaders(httpRequest);

		log.info("Received MCP request: method={}, id={}, forwardedHeaders={}", method, id, forwardedHeaders.keySet());

		// 2. 认证校验
		try {
			authProvider.authenticate(forwardedHeaders);
		} catch (GatewayAuthenticationException e) {
			log.warn("Authentication failed for MCP request: method={}, reason={}", method, e.getMessage());
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
					.body(buildErrorResponse(id, -32001, "Authentication failed: " + e.getMessage()));
		}

		if (method == null) {
			return ResponseEntity.badRequest().body(buildErrorResponse(id, -32600, "Invalid Request: method is missing"));
		}

		// 3. JSON-RPC 方法分发
        return switch (method) {
            case "initialize" -> ResponseEntity.ok(handleInitialize(id));
            case "notifications/initialized" -> ResponseEntity.status(HttpStatus.NO_CONTENT).build();
            case "tools/list" -> ResponseEntity.ok(handleToolsList(id, forwardedHeaders));
            case "tools/call" -> handleToolsCall(id, requestBody, forwardedHeaders);
            default -> {
                log.warn("Unsupported MCP method requested: {}", method);
                yield ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(buildErrorResponse(id, -32601, "Method not found: " + method));
            }
        };
	}

	/**
	 * 从 HttpServletRequest 中根据配置提取需要转发的 Header。
	 * Header key 的比对不区分大小写，提取后 key 保持原始配置中的写法。
	 */
	private Map<String, String> extractForwardHeaders(HttpServletRequest request) {
		List<String> configuredKeys = properties.getForwardHeaders();
		Map<String, String> result = new LinkedHashMap<>();
		if (configuredKeys == null || configuredKeys.isEmpty()) {
			return result;
		}
		for (String key : configuredKeys) {
			String value = request.getHeader(key);
			if (value != null) {
				result.put(key, value);
			}
		}
		return result;
	}

	private Map<String, Object> handleInitialize(Object id) {
		Map<String, Object> result = Map.of(
				"protocolVersion", "2025-03-26",
				"capabilities", Map.of("tools", Map.of("listChanged", false)),
				"serverInfo", Map.of(
						"name", properties.getServerName(),
						"version", properties.getServerVersion()
				)
		);
		return buildSuccessResponse(id, result);
	}

	private Map<String, Object> handleToolsList(Object id, Map<String, String> headers) {
		List<Map<String, Object>> tools = catalogService.listTools(headers);
		return buildSuccessResponse(id, Map.of("tools", tools));
	}

	@SuppressWarnings("unchecked")
	private ResponseEntity<Map<String, Object>> handleToolsCall(Object id, Map<String, Object> requestBody, Map<String, String> headers) {
		Map<String, Object> params = (Map<String, Object>) requestBody.get("params");
		if (params == null) {
			return ResponseEntity.badRequest().body(buildErrorResponse(id, -32602, "Invalid params: params object is missing"));
		}

		String name = (String) params.get("name");
		if (name == null || name.isBlank()) {
			return ResponseEntity.badRequest().body(buildErrorResponse(id, -32602, "Invalid params: tool name is missing"));
		}

		Map<String, Object> arguments = (Map<String, Object>) params.get("arguments");
		Map<String, Object> callResult = invocationService.invokeTool(name, arguments, headers);

		return ResponseEntity.ok(buildSuccessResponse(id, callResult));
	}

	private Map<String, Object> buildSuccessResponse(Object id, Object result) {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("jsonrpc", "2.0");
		response.put("id", id);
		response.put("result", result);
		return response;
	}

	private Map<String, Object> buildErrorResponse(Object id, int code, String message) {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("jsonrpc", "2.0");
		response.put("id", id);
		response.put("error", Map.of("code", code, "message", message));
		return response;
	}
}
