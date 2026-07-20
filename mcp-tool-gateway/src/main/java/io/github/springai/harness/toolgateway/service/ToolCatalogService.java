package io.github.springai.harness.toolgateway.service;

import io.github.springai.harness.toolgateway.catalog.ToolCatalogProvider;
import io.github.springai.harness.toolgateway.catalog.ToolDefinition;
import io.github.springai.harness.toolgateway.filter.ToolPermissionFilter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具目录服务。
 * 组合加载器与权限过滤器，向 Controller 提供过滤后的工具列表和查找能力。
 *
 * @author ichaobuster
 */
@Slf4j
public class ToolCatalogService {

	private final ToolCatalogProvider catalogProvider;
	private final ToolPermissionFilter permissionFilter;

	public ToolCatalogService(ToolCatalogProvider catalogProvider, ToolPermissionFilter permissionFilter) {
		this.catalogProvider = catalogProvider;
		this.permissionFilter = permissionFilter;
	}

	/**
	 * 获取过滤后的可用工具列表，格式化为 MCP 规范所需的 Map 结构。
	 *
	 * @param headers 从请求中提取的转发 Header Map
	 * @return 适用于 MCP Response 的工具列表
	 */
	public List<Map<String, Object>> listTools(Map<String, String> headers) {
		log.info("Listing tools, forwarded headers keys: {}", headers != null ? headers.keySet() : "null");
		List<ToolDefinition> allTools = catalogProvider.loadAll();
		
		// 1. 过滤未启用的工具
		List<ToolDefinition> enabledTools = allTools.stream()
				.filter(ToolDefinition::isEnabled)
				.toList();

		// 2. 权限过滤
		List<ToolDefinition> allowedTools = permissionFilter.filter(enabledTools, headers);
		if (allowedTools == null) {
			allowedTools = new ArrayList<>();
		}

		// 3. 转换为 MCP Protocol JSON 结构
		List<Map<String, Object>> mcpTools = new ArrayList<>();
		for (ToolDefinition tool : allowedTools) {
			Map<String, Object> mcpTool = new HashMap<>();
			mcpTool.put("name", tool.name());
			mcpTool.put("description", tool.description());
			mcpTool.put("inputSchema", tool.inputSchema() != null ? tool.inputSchema() : Map.of("type", "object"));
			
			if (tool.annotations() != null) {
				Map<String, Object> annotationsMap = new HashMap<>();
				if (tool.annotations().title() != null) {
					annotationsMap.put("title", tool.annotations().title());
				}
				if (tool.annotations().readOnlyHint() != null) {
					annotationsMap.put("readOnlyHint", tool.annotations().readOnlyHint());
				}
				if (tool.annotations().destructiveHint() != null) {
					annotationsMap.put("destructiveHint", tool.annotations().destructiveHint());
				}
				if (tool.annotations().idempotentHint() != null) {
					annotationsMap.put("idempotentHint", tool.annotations().idempotentHint());
				}
				if (tool.annotations().openWorldHint() != null) {
					annotationsMap.put("openWorldHint", tool.annotations().openWorldHint());
				}
				if (!annotationsMap.isEmpty()) {
					mcpTool.put("annotations", annotationsMap);
				}
			}
			mcpTools.add(mcpTool);
		}

		log.info("Returned {} tools for this request", mcpTools.size());
		return mcpTools;
	}

	/**
	 * 根据名称查找并验证某个工具有无使用权限。
	 *
	 * @param toolName 工具名称
	 * @param headers 从请求中提取的转发 Header Map
	 * @return 可用的工具定义包
	 */
	public Optional<ToolDefinition> findTool(String toolName, Map<String, String> headers) {
		Optional<ToolDefinition> optTool = catalogProvider.findByName(toolName);
		if (optTool.isEmpty() || !optTool.get().isEnabled()) {
			return Optional.empty();
		}

		ToolDefinition tool = optTool.get();
		List<ToolDefinition> filtered = permissionFilter.filter(List.of(tool), headers);
		if (filtered == null || filtered.isEmpty()) {
			log.warn("Permission denied for tool: {}", toolName);
			return Optional.empty();
		}

		return Optional.of(tool);
	}
}
