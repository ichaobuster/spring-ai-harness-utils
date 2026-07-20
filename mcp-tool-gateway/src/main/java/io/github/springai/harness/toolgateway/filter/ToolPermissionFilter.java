package io.github.springai.harness.toolgateway.filter;

import io.github.springai.harness.toolgateway.catalog.ToolDefinition;

import java.util.List;
import java.util.Map;

/**
 * 工具权限过滤接口。
 * 根据用户的请求 Header 信息过滤出允许使用的工具列表。
 *
 * @author ichaobuster
 */
public interface ToolPermissionFilter {

	/**
	 * 根据用户信息过滤工具列表。
	 *
	 * @param tools 开启的工具列表
	 * @param headers 从请求中提取的转发 Header Map
	 * @return 过滤后允许该用户使用的工具列表
	 */
	List<ToolDefinition> filter(List<ToolDefinition> tools, Map<String, String> headers);
}
