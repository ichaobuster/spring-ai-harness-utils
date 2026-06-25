package io.github.springai.harness.sandbox;

import io.github.springai.harness.mcp.AgentMcpClients;
import io.github.springai.harness.mcp.McpConfigSpec;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LazyLoadSandboxCreatorTest {

	@Mock
	AgentMcpClients mockAgentMcpClients;

	MockedStatic<McpToolUtils> mockedMcpToolUtils;

	McpConfigSpec.McpServerConfig mcpServerConfig;

	LazyLoadSandboxCreator creator;

	@BeforeEach
	void setup() {
		mockedMcpToolUtils = mockStatic(McpToolUtils.class);
		mcpServerConfig = new McpConfigSpec.McpServerConfig("streamable-http", "http://example.com", Map.of());
		creator = new LazyLoadSandboxCreator(mockAgentMcpClients, mcpServerConfig);
	}

	@AfterEach
	void teardown() {
		mockedMcpToolUtils.close();
	}

	@Test
	void createToolService() {
		McpSyncClient mockMcpClient = mock(McpSyncClient.class);
		when(mockAgentMcpClients.createMcpSyncClient(anyString(), anyString(), eq(mcpServerConfig))).thenReturn(mockMcpClient);
		mockedMcpToolUtils.when(() -> McpToolUtils.getToolCallbacksFromSyncClients(anyList())).thenReturn(List.of(
						"run_shell_command",
						"browser_click",
						"not_exists"
				).stream().map(toolName -> FunctionToolCallback.builder(toolName, (Function<Map<String, Object>, String>) (args) -> "hello")
						.description("description of " + toolName)
						.inputType(Map.class)
						.build())
				.collect(Collectors.toList()));

		LazyLoadSandboxToolsService result1 = creator.createToolService("agent1");
		LazyLoadSandboxToolsService result2 = creator.createToolService("agent2");

		assertThat(result1).isNotSameAs(result2);
	}

}