package io.github.springai.harness.sandbox;

import io.github.springai.harness.mcp.AgentMcpClients;
import io.github.springai.harness.mcp.McpConfigSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LazyLoadSandboxToolsServiceTest {

	@Mock
	AgentMcpClients mockAgentMcpClients;

	@Mock
	McpSyncClient mockMcpSyncClient;

	MockedStatic<McpToolUtils> mockedMcpToolUtils;

	McpConfigSpec.McpServerConfig mcpServerConfig;

	ObjectMapper objectMapper = new ObjectMapper();

	List<ToolDefinition> toolDefinitionList = new ArrayList<>();

	LazyLoadSandboxToolsService service;

	@BeforeEach
	void setup() {
		mockedMcpToolUtils = mockStatic(McpToolUtils.class);
		mcpServerConfig = new McpConfigSpec.McpServerConfig("streamable-http", "http://example.com", Map.of());
		toolDefinitionList.addAll(List.of(
						"run_shell_command",
						"browser_click"
				).stream().map(toolName -> ToolDefinition.builder()
						.name(toolName)
						.description("description of " + toolName)
						.inputSchema("{}")
						.build())
				.collect(Collectors.toList()));
		service = new LazyLoadSandboxToolsService("testAgent", mockAgentMcpClients, mcpServerConfig, toolDefinitionList);
	}

	@AfterEach
	void teardown() {
		mockedMcpToolUtils.close();
	}

	@Test
	void getSandboxTools() {
		when(mockAgentMcpClients.createMcpSyncClient(eq("testAgent"), anyString(), eq(mcpServerConfig))).thenReturn(mockMcpSyncClient);
		mockedMcpToolUtils.when(() -> McpToolUtils.getToolCallbacksFromSyncClients(anyList())).thenReturn(List.of(
						"run_shell_command",
						"browser_click",
						"not_exists"
				).stream().map(toolName -> FunctionToolCallback.builder(toolName, (Function<Map<String, Object>, String>) (args) -> "hello")
						.description("description of " + toolName)
						.inputType(Map.class)
						.build())
				.collect(Collectors.toList()));

		List<ToolCallback> result = service.getSandboxToolCallbacks();

		assertThat(result).hasSize(2);
		assertThat(result).anyMatch(t -> t.getToolDefinition().name().equals("run_shell_command"))
				.anyMatch(t -> t.getToolDefinition().name().equals("browser_click"))
				.noneMatch(t -> t.getToolDefinition().name().equals("not_exists"));

		String toolResult1 = result.get(0).call("{\"foo\": \"bar\"}");
		assertThat(toolResult1).isEqualTo("\"hello\"");

		// second run
		String toolResult2 = result.get(0).call("{\"foo\": \"bar\"}");
		assertThat(toolResult2).isEqualTo("\"hello\"");
	}

	@Test
	void closeMcpClient_mcpClientIsNull() {
		service.closeMcpClient();
	}

	@Test
	void closeMcpClient() {
		ReflectionTestUtils.setField(service, "mcpSyncClient", mockMcpSyncClient);
		doNothing().when(mockAgentMcpClients).closeMcpSyncClients(anyList());
		service.closeMcpClient();
	}
}