package io.github.springai.harness.mcp;

import io.github.springai.harness.config.AgentConfig;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentMcpClientsTest {

	AgentMcpClients agentMcpClients = new AgentMcpClients();

	@Test
	void testGetMcpSyncClients_EmptyConfig() {
		assertThat(agentMcpClients.getMcpSyncClients(new AgentConfig())).isEmpty();
	}

	@Test
	void testGetMcpSyncClients_UnsupportedType() {
		AgentConfig config = new AgentConfig("test_mcp.user-4");
		config.getMcpServers().put("all-in-one", new McpConfigSpec.McpServerConfig("stdio",
				"http://example.com",
				Map.of("Authorization", "Bearer sk_xxxx")));

		assertThatThrownBy(() -> agentMcpClients.getMcpSyncClients(config))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Unsupported transport type");
	}

	@Test
	void testGetMcpSyncClients_Success() {
		AgentConfig config = new AgentConfig("test_mcp.user-4");
		config.getMcpServers().put("mcp-server1", new McpConfigSpec.McpServerConfig("streamable-http",
				"http://example.com",
				Map.of("Authorization", "Bearer sk_xxxx")));
		config.getMcpServers().put("mcp-server2", new McpConfigSpec.McpServerConfig("sse",
				"http://example.com",
				Map.of("Authorization", "Bearer sk_xxxx")));

		McpSyncClient mockClient1 = mock(McpSyncClient.class);
		McpSyncClient mockClient2 = mock(McpSyncClient.class);
		McpClient.SyncSpec mockSyncSpec = mock(McpClient.SyncSpec.class);
		McpSchema.InitializeResult mockInitResult = mock(McpSchema.InitializeResult.class);

		when(mockSyncSpec.requestTimeout(any(Duration.class))).thenReturn(mockSyncSpec);
		when(mockSyncSpec.build()).thenReturn(mockClient1, mockClient2);
		when(mockClient1.initialize()).thenReturn(mockInitResult);
		when(mockClient2.initialize()).thenReturn(mockInitResult);

		// 使用 try-with-resources 拦截静态方法调用
		try (MockedStatic<McpClient> mcpClientMockedStatic = mockStatic(McpClient.class)) {
			// 拦截 McpClient.sync(...) 让其返回我们的 mockBuilder
			mcpClientMockedStatic.when(() -> McpClient.sync(any())).thenReturn(mockSyncSpec);

			List<McpSyncClient> result = agentMcpClients.getMcpSyncClients(config);
			assertThat(result).hasSize(2);
		}
	}

	@Test
	void testGetMcpSyncClients_retryAndFailed() {
		AgentConfig config = new AgentConfig("test_mcp.user-4");
		config.getMcpServers().put("mcp-server1", new McpConfigSpec.McpServerConfig("streamable-http",
				"http://example.com",
				Map.of("Authorization", "Bearer sk_xxxx")));
		config.getMcpServers().put("mcp-server2", new McpConfigSpec.McpServerConfig("sse",
				"http://example.com",
				Map.of("Authorization", "Bearer sk_xxxx")));

		McpSyncClient mockClient1 = mock(McpSyncClient.class);
		McpClient.SyncSpec mockSyncSpec = mock(McpClient.SyncSpec.class);

		when(mockSyncSpec.requestTimeout(any(Duration.class))).thenReturn(mockSyncSpec);
		when(mockSyncSpec.build()).thenReturn(mockClient1);
		doThrow(new RuntimeException("Client failed to initialize by explicit API call")).when(mockClient1).initialize();

		// 使用 try-with-resources 拦截静态方法调用
		try (MockedStatic<McpClient> mcpClientMockedStatic = mockStatic(McpClient.class)) {
			// 拦截 McpClient.sync(...) 让其返回我们的 mockBuilder
			mcpClientMockedStatic.when(() -> McpClient.sync(any())).thenReturn(mockSyncSpec);

			assertThatThrownBy(() -> agentMcpClients.getMcpSyncClients(config))
					.isInstanceOf(RuntimeException.class)
					.hasMessageContaining("Failed to initialize mcp client after");
		}
	}

	@Test
	void testGetMcpSyncClients_failedWithOtherError() {
		AgentConfig config = new AgentConfig("test_mcp.user-4");
		config.getMcpServers().put("mcp-server1", new McpConfigSpec.McpServerConfig("streamable-http",
				"http://example.com",
				Map.of("Authorization", "Bearer sk_xxxx")));
		config.getMcpServers().put("mcp-server2", new McpConfigSpec.McpServerConfig("sse",
				"http://example.com",
				Map.of("Authorization", "Bearer sk_xxxx")));

		McpSyncClient mockClient1 = mock(McpSyncClient.class);
		McpClient.SyncSpec mockSyncSpec = mock(McpClient.SyncSpec.class);

		when(mockSyncSpec.requestTimeout(any(Duration.class))).thenReturn(mockSyncSpec);
		when(mockSyncSpec.build()).thenReturn(mockClient1);
		doThrow(new RuntimeException("Other error")).when(mockClient1).initialize();

		// 使用 try-with-resources 拦截静态方法调用
		try (MockedStatic<McpClient> mcpClientMockedStatic = mockStatic(McpClient.class)) {
			// 拦截 McpClient.sync(...) 让其返回我们的 mockBuilder
			mcpClientMockedStatic.when(() -> McpClient.sync(any())).thenReturn(mockSyncSpec);

			assertThatThrownBy(() -> agentMcpClients.getMcpSyncClients(config))
					.isInstanceOf(RuntimeException.class)
					.hasMessage("Other error");
		}
	}

	@Test
	void testcloseMcpSyncClients() {
		McpSyncClient mockClient = mock(McpSyncClient.class);
//		when(mockClient.closeGracefully()).thenReturn(true);
		doNothing().when(mockClient).close();
		agentMcpClients.closeMcpSyncClients(List.of(mockClient));
	}
}