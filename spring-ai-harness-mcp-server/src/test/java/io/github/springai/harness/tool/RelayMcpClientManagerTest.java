package io.github.springai.harness.tool;

import io.github.springai.harness.auth.AuthenticationException;
import io.github.springai.harness.auth.AuthenticationProvider;
import io.github.springai.harness.auth.WorkspaceIdentity;
import io.github.springai.harness.autoconfig.HarnessMcpServerProperties;
import io.github.springai.harness.autoconfig.HarnessMcpServerProperties.RelayProperties;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.function.ServerRequest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RelayMcpClientManager}.
 *
 * @author Antigravity
 */
@DisplayName("RelayMcpClientManager Tests")
@ExtendWith(MockitoExtension.class)
class RelayMcpClientManagerTest {

	private HarnessMcpServerProperties properties;
	private RelayMcpClientManager manager;

	@Mock
	private AuthenticationProvider authenticationProvider;

	@Mock
	private ServerRequest serverRequest;

	@BeforeEach
	void setUp() {
		properties = new HarnessMcpServerProperties();
		RelayProperties relayProperties = properties.getRelay();
		relayProperties.setEnabled(true);
		relayProperties.setUrl("http://localhost:8081");
		relayProperties.getHeaders().put("Authorization", "Bearer static-token");

		manager = new RelayMcpClientManager(properties, authenticationProvider);
	}

	@Test
	@DisplayName("Should successfully create a client instance")
	void shouldCreateClient() {
		McpSyncClient mockClient = mock(McpSyncClient.class);
		McpClient.SyncSpec mockSyncSpec = mock(McpClient.SyncSpec.class);
		McpSchema.InitializeResult mockInitResult = mock(McpSchema.InitializeResult.class);

		WorkspaceIdentity identity = new WorkspaceIdentity("sys", "ag", "usr");
		when(authenticationProvider.authenticate(serverRequest)).thenReturn(identity);
		when(mockSyncSpec.requestTimeout(any(Duration.class))).thenReturn(mockSyncSpec);
		when(mockSyncSpec.build()).thenReturn(mockClient);
		when(mockClient.initialize()).thenReturn(mockInitResult);

		try (MockedStatic<McpClient> mcpClientMockedStatic = mockStatic(McpClient.class)) {
			mcpClientMockedStatic.when(() -> McpClient.sync(any())).thenReturn(mockSyncSpec);

			McpSyncClient client = manager.createClient(serverRequest);
			assertThat(client).isSameAs(mockClient);

			mcpClientMockedStatic.verify(() -> McpClient.sync(any()), times(1));
		}
	}

	@Test
	@DisplayName("Should throw IllegalArgumentException when ServerRequest is null")
	void shouldThrowWhenServerRequestIsNull() {
		assertThatThrownBy(() -> manager.createClient(null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("ServerRequest must not be null");
	}

	@Test
	@DisplayName("Should throw AuthenticationException when authentication fails")
	void shouldThrowWhenAuthenticationFails() {
		when(authenticationProvider.authenticate(serverRequest)).thenThrow(new AuthenticationException("Invalid token"));

		assertThatThrownBy(() -> manager.createClient(serverRequest))
				.isInstanceOf(AuthenticationException.class)
				.hasMessageContaining("Invalid token");
	}

	@Test
	@DisplayName("Should throw IllegalStateException when relay configuration is disabled")
	void shouldThrowWhenRelayDisabled() {
		WorkspaceIdentity identity = new WorkspaceIdentity("sys", "ag", "usr");
		when(authenticationProvider.authenticate(serverRequest)).thenReturn(identity);
		properties.getRelay().setEnabled(false);

		assertThatThrownBy(() -> manager.createClient(serverRequest))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Relay configuration is disabled or URL is missing");
	}

	@Test
	@DisplayName("Should throw IllegalStateException when relay URL is missing")
	void shouldThrowWhenRelayUrlMissing() {
		WorkspaceIdentity identity = new WorkspaceIdentity("sys", "ag", "usr");
		when(authenticationProvider.authenticate(serverRequest)).thenReturn(identity);
		properties.getRelay().setUrl("");

		assertThatThrownBy(() -> manager.createClient(serverRequest))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Relay configuration is disabled or URL is missing");
	}

	@Test
	@DisplayName("Should retry initialization if first attempts fail with retryable error")
	void shouldRetryInitialization() {
		McpSyncClient mockClient = mock(McpSyncClient.class);
		McpClient.SyncSpec mockSyncSpec = mock(McpClient.SyncSpec.class);
		McpSchema.InitializeResult mockInitResult = mock(McpSchema.InitializeResult.class);

		WorkspaceIdentity identity = new WorkspaceIdentity("sys", "ag", "usr");
		when(authenticationProvider.authenticate(serverRequest)).thenReturn(identity);
		when(mockSyncSpec.requestTimeout(any(Duration.class))).thenReturn(mockSyncSpec);
		when(mockSyncSpec.build()).thenReturn(mockClient);

		// Throw retryable exception twice, then succeed
		when(mockClient.initialize())
				.thenThrow(new RuntimeException("Client failed to initialize by explicit API call"))
				.thenThrow(new RuntimeException("Client failed to initialize by explicit API call"))
				.thenReturn(mockInitResult);

		try (MockedStatic<McpClient> mcpClientMockedStatic = mockStatic(McpClient.class)) {
			mcpClientMockedStatic.when(() -> McpClient.sync(any())).thenReturn(mockSyncSpec);

			McpSyncClient client = manager.createClient(serverRequest);
			assertThat(client).isSameAs(mockClient);

			verify(mockClient, times(3)).initialize();
		}
	}

	@Test
	@DisplayName("Should fail initialization after exceeding max retry count")
	void shouldFailAfterMaxRetries() {
		McpSyncClient mockClient = mock(McpSyncClient.class);
		McpClient.SyncSpec mockSyncSpec = mock(McpClient.SyncSpec.class);

		WorkspaceIdentity identity = new WorkspaceIdentity("sys", "ag", "usr");
		when(authenticationProvider.authenticate(serverRequest)).thenReturn(identity);
		when(mockSyncSpec.requestTimeout(any(Duration.class))).thenReturn(mockSyncSpec);
		when(mockSyncSpec.build()).thenReturn(mockClient);

		// Always throw retryable exception
		when(mockClient.initialize()).thenThrow(new RuntimeException("Client failed to initialize by explicit API call"));

		try (MockedStatic<McpClient> mcpClientMockedStatic = mockStatic(McpClient.class)) {
			mcpClientMockedStatic.when(() -> McpClient.sync(any())).thenReturn(mockSyncSpec);

			assertThatThrownBy(() -> manager.createClient(serverRequest))
					.isInstanceOf(RuntimeException.class)
					.hasMessageContaining("Failed to initialize relay client after 3 retries");

			verify(mockClient, times(3)).initialize();
		}
	}

	@Test
	@DisplayName("Should close the client if initialization fails")
	void shouldCloseClientOnFailure() {
		McpSyncClient mockClient = mock(McpSyncClient.class);
		McpClient.SyncSpec mockSyncSpec = mock(McpClient.SyncSpec.class);

		WorkspaceIdentity identity = new WorkspaceIdentity("sys", "ag", "usr");
		when(authenticationProvider.authenticate(serverRequest)).thenReturn(identity);
		when(mockSyncSpec.requestTimeout(any(Duration.class))).thenReturn(mockSyncSpec);
		when(mockSyncSpec.build()).thenReturn(mockClient);
		// Fail with non-retryable exception to trigger immediate abort
		when(mockClient.initialize()).thenThrow(new RuntimeException("Immediate fatal error"));

		try (MockedStatic<McpClient> mcpClientMockedStatic = mockStatic(McpClient.class)) {
			mcpClientMockedStatic.when(() -> McpClient.sync(any())).thenReturn(mockSyncSpec);

			assertThatThrownBy(() -> manager.createClient(serverRequest))
					.isInstanceOf(RuntimeException.class)
					.hasMessageContaining("Immediate fatal error");

			verify(mockClient, times(1)).close();
		}
	}
}
