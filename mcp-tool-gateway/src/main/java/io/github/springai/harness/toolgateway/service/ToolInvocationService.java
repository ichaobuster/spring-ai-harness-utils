package io.github.springai.harness.toolgateway.service;

import io.github.springai.harness.toolgateway.catalog.HttpEndpointConfig;
import io.github.springai.harness.toolgateway.catalog.ToolDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 负责工具调用的 HTTP Bypass 转发服务。
 * 将 MCP 的 tools/call 请求转换为对外部 HTTP API 的调用，并将响应结果封装回 MCP 规范格式。
 *
 * @author ichaobuster
 */
@Slf4j
public class ToolInvocationService {

	private final ToolCatalogService catalogService;
	private final RestClient restClient;

	public ToolInvocationService(ToolCatalogService catalogService, RestClient restClient) {
		this.catalogService = catalogService;
		this.restClient = restClient;
	}

	/**
	 * 执行工具调用。
	 *
	 * @param toolName 工具名称
	 * @param arguments 工具调用参数
	 * @param headers 从原始请求中提取的转发 Header Map（用于权限校验和透传）
	 * @return MCP 规范的 CallToolResult 结构 Map
	 */
	public Map<String, Object> invokeTool(String toolName, Map<String, Object> arguments, Map<String, String> headers) {
		log.info("Invoking tool: {} with arguments keys: {}", toolName, arguments != null ? arguments.keySet() : "null");

		Map<String, String> safeHeaders = headers != null ? headers : Collections.emptyMap();

		// 1. 查找并鉴权工具
		Optional<ToolDefinition> optTool = catalogService.findTool(toolName, safeHeaders);
		if (optTool.isEmpty()) {
			return buildErrorResult("Tool not found or permission denied: " + toolName);
		}

		ToolDefinition tool = optTool.get();
		HttpEndpointConfig config = tool.httpEndpoint();
		if (config == null || config.url() == null || config.url().isBlank()) {
			return buildErrorResult("Tool " + toolName + " is not configured with a valid HTTP endpoint.");
		}

		// 2. 发起 HTTP Bypass 请求
		try {
			String method = config.methodOrDefault();
			String url = config.url();

			log.info("Bypassing tool call {} to external API: {} {}", toolName, method, url);

			RestClient.RequestBodyUriSpec requestSpec = restClient.method(HttpMethod.valueOf(method));
			
			// 3. 构建请求 URI (自动替换占位符，例如 url 为 https://api.com?city={city})
			RestClient.RequestBodySpec requestBodySpec;
			if (arguments != null && !arguments.isEmpty()) {
				requestBodySpec = requestSpec.uri(url, arguments);
			} else {
				requestBodySpec = requestSpec.uri(url);
			}

			// 4. 设置工具自身配置的静态 Headers
			if (config.headers() != null) {
				config.headers().forEach(requestBodySpec::header);
			}
			requestBodySpec.header("Content-Type", config.contentTypeOrDefault());

			// 5. 透传转发 Headers（如果工具配置了 forwardAuthHeader）
			if (config.shouldForwardAuth()) {
				safeHeaders.forEach((key, value) -> {
					if (value != null && !value.isBlank()) {
						requestBodySpec.header(key, value);
					}
				});
			}

			// 6. 设置 Request Body (仅对 POST/PUT/PATCH)
			if (arguments != null && ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method))) {
				requestBodySpec.body(arguments);
			}

			// 7. 执行调用
			ResponseEntity<String> response = requestBodySpec.retrieve().toEntity(String.class);
			
			log.info("Tool invocation {} completed with HTTP status: {}", toolName, response.getStatusCode());
			return buildSuccessResult(response.getBody());

		} catch (HttpStatusCodeException e) {
			log.error("HTTP error occurred during tool invocation " + toolName + ": " + e.getStatusCode(), e);
			return buildErrorResult("HTTP error " + e.getStatusCode() + ": " + e.getResponseBodyAsString());
		} catch (Exception e) {
			log.error("Unexpected error occurred during tool invocation " + toolName, e);
			return buildErrorResult("Invocation failed: " + e.getMessage());
		}
	}

	private Map<String, Object> buildSuccessResult(String text) {
		String bodyText = text != null ? text : "";
		return Map.of(
				"content", List.of(Map.of("type", "text", "text", bodyText)),
				"isError", false
		);
	}

	private Map<String, Object> buildErrorResult(String errorMessage) {
		return Map.of(
				"content", List.of(Map.of("type", "text", "text", errorMessage)),
				"isError", true
		);
	}
}
