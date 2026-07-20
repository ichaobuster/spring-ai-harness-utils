package io.github.springai.harness.toolgateway.filter;

import io.github.springai.harness.toolgateway.catalog.ToolDefinition;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 默认实现：允许所有工具使用（不过滤）。
 * 作为一个预留的扩展点，供测试或无需过滤的场景使用。
 *
 * @author ichaobuster
 */
@Slf4j
public class AllowAllToolPermissionFilter implements ToolPermissionFilter {

	@Override
	public List<ToolDefinition> filter(List<ToolDefinition> tools, Map<String, String> headers) {
		log.debug("AllowAllToolPermissionFilter bypassed filtering, headers keys: {}", headers != null ? headers.keySet() : "null");
		if (tools == null) {
			return new ArrayList<>();
		}
		return new ArrayList<>(tools);
	}
}
