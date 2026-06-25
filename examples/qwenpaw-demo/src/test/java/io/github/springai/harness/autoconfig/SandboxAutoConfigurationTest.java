package io.github.springai.harness.autoconfig;

import io.github.springai.harness.HarnessAgentsProperties;
import io.github.springai.harness.mcp.AgentMcpClients;
import io.github.springai.harness.mcp.McpConfigSpec;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SandboxAutoConfigurationTest {

	@Test
	void lazyLoadSandboxCreator() {
		McpConfigSpec.McpServerConfig mcpServerConfig = new McpConfigSpec.McpServerConfig("streamable-http", "http://example.com", Map.of());
		HarnessAgentsProperties properties = new HarnessAgentsProperties();
		properties.setSandboxMcp(mcpServerConfig);
		var result = new SandboxAutoConfiguration()
				.lazyLoadSandboxCreator(mock(AgentMcpClients.class), properties);
		assertThat(result).isNotNull();
	}

}