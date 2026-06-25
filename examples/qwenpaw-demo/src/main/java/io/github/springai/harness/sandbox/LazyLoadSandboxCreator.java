package io.github.springai.harness.sandbox;

import io.github.springai.harness.mcp.AgentMcpClients;
import io.github.springai.harness.mcp.McpConfigSpec;
import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.Assert;

import java.util.List;
import java.util.stream.Collectors;

/**
 * SandboxMcpClientCreator 创建懒加载沙箱 MCP 的 creator <br/>
 * 懒加载沙箱 MCP 为了解决即使 agent 不使用沙箱里的工具，也会因为创建 mcp client 而导致沙箱 pod 被创建出来，造成资源浪费的问题
 *
 * @author ichaobuster
 */
public class LazyLoadSandboxCreator {

	private static final String SERVER_NAME = "sandbox-mcp";

	private static final String AGENT_ID = "administrator";

	private static final List<String> ALLOWED_TOOL_NAMES = List.of(
			"run_shell_command",
			"run_ipython_cell", // TODO python 执行用沙箱还是用 PythonService
			"browser_close",
			"browser_resize",
			"browser_console_messages",
			"browser_handle_dialog",
			"browser_file_upload",
			"browser_press_key",
			"browser_navigate",
			"browser_navigate_back",
			"browser_navigate_forward",
			"browser_network_requests",
			"browser_pdf_save",
			"browser_take_screenshot",
			"browser_snapshot",
			"browser_click",
			"browser_drag",
			"browser_hover",
			"browser_type",
			"browser_select_option",
			"browser_tab_list",
			"browser_tab_new",
			"browser_tab_select",
			"browser_tab_close",
			"browser_wait_for"
	);

	private final AgentMcpClients agentMcpClients;

	private final McpConfigSpec.McpServerConfig sandboxMcpConfig;

	private List<ToolDefinition> mcpToolDefinitions;

	public LazyLoadSandboxCreator(AgentMcpClients agentMcpClients, McpConfigSpec.McpServerConfig sandboxMcpConfig) {
		Assert.notNull(agentMcpClients, "agentMcpClients must not be null");
		Assert.notNull(sandboxMcpConfig, "sandboxMcpConfig must not be null");

		this.agentMcpClients = agentMcpClients;
		this.sandboxMcpConfig = sandboxMcpConfig;
	}

	public LazyLoadSandboxToolsService createToolService(String agentId) {
		if (this.mcpToolDefinitions == null) {
			this.mcpToolDefinitions = getMcpToolDefinitions(this.sandboxMcpConfig);
		}
		return new LazyLoadSandboxToolsService(agentId, this.agentMcpClients, this.sandboxMcpConfig, this.mcpToolDefinitions);
	}

	private List<ToolDefinition> getMcpToolDefinitions(McpConfigSpec.McpServerConfig sandboxMcpConfig) {
		McpSyncClient tmpMcpClient = agentMcpClients.createMcpSyncClient(AGENT_ID, SERVER_NAME, sandboxMcpConfig);
		List<ToolCallback> mcpToolCallbacks = McpToolUtils.getToolCallbacksFromSyncClients(List.of(tmpMcpClient));
		return mcpToolCallbacks.stream()
				.filter(tool -> ALLOWED_TOOL_NAMES.contains(tool.getToolDefinition().name()))
				.map(tool -> tool.getToolDefinition())
				.collect(Collectors.toList());
	}


}
