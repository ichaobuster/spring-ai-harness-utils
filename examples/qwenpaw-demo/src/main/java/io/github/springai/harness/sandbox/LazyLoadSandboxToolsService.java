package io.github.springai.harness.sandbox;

import io.github.springai.harness.mcp.AgentMcpClients;
import io.github.springai.harness.mcp.McpConfigSpec;
import io.modelcontextprotocol.client.McpSyncClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.util.json.JsonParser;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * SandboxToolsService 懒加载沙箱 MCP 服务 <br/>
 * * 懒加载沙箱 MCP 为了解决即使 agent 不使用沙箱里的工具，也会因为创建 mcp client 而导致沙箱 pod 被创建出来，造成资源浪费的问题
 *
 * @author ichaobuster
 */
@Slf4j
public class LazyLoadSandboxToolsService {

	private static final String SERVER_NAME = "sandbox-mcp";

	private final String agentId;

	private final McpConfigSpec.McpServerConfig sandboxMcpConfig;

	private final AgentMcpClients agentMcpClients;

	private final List<ToolDefinition> sandboxToolDefinitions;

	private McpSyncClient mcpSyncClient;

	private List<ToolCallback> cachedRealMcpToolCallbacks;

	public LazyLoadSandboxToolsService(String agentId, AgentMcpClients agentMcpClients, McpConfigSpec.McpServerConfig sandboxMcpConfig, List<ToolDefinition> sandboxToolDefinitions) {
		Assert.hasText(agentId, "agentId must not be empty");
		Assert.notNull(sandboxMcpConfig, "sandboxMcpConfig must not be null");
		Assert.notNull(agentMcpClients, "agentMcpClients must not be null");
		Assert.notNull(sandboxToolDefinitions, "sandboxToolDefinitions must not be null");

		this.agentId = agentId;
		this.sandboxMcpConfig = sandboxMcpConfig;
		this.agentMcpClients = agentMcpClients;
		this.sandboxToolDefinitions = sandboxToolDefinitions;
	}

	/**
	 * 获取沙箱工具
	 */
	public List<ToolCallback> getSandboxToolCallbacks() {
		return this.sandboxToolDefinitions.stream()
				.map(toolDefinition ->
						FunctionToolCallback.builder(toolDefinition.name(), (Function<Map<String, Object>, String>) (args) ->
										{
											try {
												return getRealTool(toolDefinition.name()).call(JsonParser.toJson(args));
											} catch (Exception e) {
												log.error("Failed to call tool: " + toolDefinition.name(), e);
												return "Failed to call tool : " + toolDefinition.name();
											}
										}
								)
								.description(toolDefinition.description())
								.inputSchema(toolDefinition.inputSchema())
								.inputType(Map.class)
								.build())
				.collect(Collectors.toList());
	}

	/**
	 * 关闭 MCP Client 连接<br/>
	 * 重要！使用后不关闭连接可能造成堆外内存泄漏
	 */
	public void closeMcpClient() {
		if (this.mcpSyncClient == null) {
			return;
		}
		this.agentMcpClients.closeMcpSyncClients(List.of(this.mcpSyncClient));
	}

	private McpSyncClient getMcpSyncClient() {
		if (this.mcpSyncClient != null) {
			return this.mcpSyncClient;
		}
		this.mcpSyncClient = this.agentMcpClients.createMcpSyncClient(this.agentId, SERVER_NAME, sandboxMcpConfig);
		return this.mcpSyncClient;
	}

	private List<ToolCallback> getRealMcpToolCallbacks() {
		if (this.cachedRealMcpToolCallbacks != null) {
			return this.cachedRealMcpToolCallbacks;
		}
		this.cachedRealMcpToolCallbacks = McpToolUtils.getToolCallbacksFromSyncClients(List.of(getMcpSyncClient()));
		return this.cachedRealMcpToolCallbacks;
	}

	private ToolCallback getRealTool(String toolName) {
		return getRealMcpToolCallbacks().stream()
				.filter(tool -> tool.getToolDefinition().name().equals(toolName))
				.findFirst()
				.orElseThrow(() -> new RuntimeException("Failed to call tool: " + toolName));
	}

}
