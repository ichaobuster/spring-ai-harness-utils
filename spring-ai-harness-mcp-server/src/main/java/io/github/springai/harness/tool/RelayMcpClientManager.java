package io.github.springai.harness.tool;

import io.github.springai.harness.auth.AuthenticationProvider;
import io.github.springai.harness.auth.WorkspaceIdentity;
import io.github.springai.harness.autoconfig.HarnessMcpServerProperties;
import io.github.springai.harness.autoconfig.HarnessMcpServerProperties.RelayProperties;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerRequest;

import java.net.http.HttpRequest;
import java.time.Duration;

/**
 * Manages creation of downstream streamable-http MCP client instances.
 * Performs creation and initialization dynamically per request to prevent connection accumulation.
 *
 * @author Antigravity
 */
@Component
@ConditionalOnProperty(prefix = "spring.ai.harness.mcp.server.relay", name = "enabled", havingValue = "true")
@Slf4j
public class RelayMcpClientManager {

	private static final Integer MCP_REQUEST_TO_IN_SEC = 120;
	private static final Integer MAX_INIT_RETRY_COUNT = 3;
	private static final String ERR_MSG_CAN_RETRY = "Client failed to initialize by explicit API call";

	private final HarnessMcpServerProperties properties;
	private final AuthenticationProvider authenticationProvider;

	public RelayMcpClientManager(HarnessMcpServerProperties properties, AuthenticationProvider authenticationProvider) {
		this.properties = properties;
		this.authenticationProvider = authenticationProvider;
	}

	/**
	 * Creates and initializes a new McpSyncClient instance for the user session.
	 * Callers are responsible for closing the returned client to prevent resource leaks.
	 */
	public McpSyncClient createClient(ServerRequest serverRequest) {
		if (serverRequest == null) {
			throw new IllegalArgumentException("ServerRequest must not be null");
		}

		WorkspaceIdentity identity = authenticationProvider.authenticate(serverRequest);
		String sessionId = identity.getSessionId();

		RelayProperties relayProperties = properties.getRelay();
		if (relayProperties == null || !relayProperties.isEnabled() || relayProperties.getUrl() == null || relayProperties.getUrl().isBlank()) {
			throw new IllegalStateException("Relay configuration is disabled or URL is missing");
		}

		HttpRequest.Builder httpRequestBuilder = HttpRequest.newBuilder();
		// Set dynamic session ID from system-agent-user
		httpRequestBuilder.header("x-agentrun-session-id", sessionId);
		// Set configured headers (map of static headers)
		if (relayProperties.getHeaders() != null) {
			relayProperties.getHeaders().forEach(httpRequestBuilder::header);
		}

		McpClientTransport transport = HttpClientStreamableHttpTransport.builder(relayProperties.getUrl())
				.endpoint("/")
				.requestBuilder(httpRequestBuilder)
				.build();

		McpSyncClient client = McpClient.sync(transport)
				.requestTimeout(Duration.ofSeconds(MCP_REQUEST_TO_IN_SEC))
				.build();

		try {
			retryInitialize(client);
		} catch (Exception e) {
			try {
				client.close();
			} catch (Exception ex) {
				log.warn("Failed to close client after initialization failure: {}", ex.getMessage());
			}
			throw e;
		}

		log.info("Successfully created and initialized relay client for user session");
		return client;
	}

	private void retryInitialize(McpSyncClient mcpSyncClient) {
		for (int i = 1; i <= MAX_INIT_RETRY_COUNT; i++) {
			try {
				mcpSyncClient.initialize();
				return;
			} catch (RuntimeException e) {
				if (e.getMessage() == null || !e.getMessage().contains(ERR_MSG_CAN_RETRY)) {
					log.error("Failed to initialize relay client: {}", e.getMessage(), e);
					throw e;
				}
				log.info("Retrying to initialize relay client: {}/{}", i, MAX_INIT_RETRY_COUNT);
			}
		}
		throw new RuntimeException("Failed to initialize relay client after " + MAX_INIT_RETRY_COUNT + " retries");
	}
}
