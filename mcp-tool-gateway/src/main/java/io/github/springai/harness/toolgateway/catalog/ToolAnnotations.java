package io.github.springai.harness.toolgateway.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * MCP Tool Annotations (spec 2025-03-26).
 * 提供给大模型的工具行为提示。
 *
 * @author ichaobuster
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolAnnotations(
		String title,
		Boolean readOnlyHint,
		Boolean destructiveHint,
		Boolean idempotentHint,
		Boolean openWorldHint
) {
	/**
	 * 提供默认的空注解实例。
	 *
	 * @return 空注解实例
	 */
	public static ToolAnnotations empty() {
		return new ToolAnnotations(null, null, null, null, null);
	}
}
