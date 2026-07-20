package io.github.springai.harness.toolgateway.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 完整的工具定义。
 * 包含 MCP 协议所需要的 metadata 字段，以及自定义的 HTTP API 端点配置。
 *
 * @author ichaobuster
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolDefinition(
		/** 工具唯一标识名 */
		String name,
		/** 工具描述（给 LLM 阅读） */
		String description,
		/** JSON Schema 格式的输入参数定义 */
		Map<String, Object> inputSchema,
		/** 输出 Schema（MCP 标准外的扩展字段，供参考） */
		Map<String, Object> outputSchema,
		/** MCP Tool Annotations */
		ToolAnnotations annotations,
		/** HTTP 转发配置 */
		HttpEndpointConfig httpEndpoint,
		/** 工具标签，用于权限分组等 */
		List<String> tags,
		/** 是否启用，默认 true */
		Boolean enabled
) {
	/**
	 * 判断工具是否启用
	 */
	public boolean isEnabled() {
		return enabled == null || enabled;
	}
}
