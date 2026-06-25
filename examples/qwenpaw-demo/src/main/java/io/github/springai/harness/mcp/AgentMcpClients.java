package io.github.springai.harness.mcp;

import io.github.springai.harness.config.AgentConfig;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AgentMcpClients
 *
 * @author ichaobuster
 */
@Slf4j
@Component
public class AgentMcpClients {

	private static final Integer MCP_REQUEST_TO_IN_SEC = 120;

	private static final Integer MAX_INIT_RETRY_COUNT = 3;

	private static final String ERR_MSG_CAN_RETRY = "Client failed to initialize by explicit API call";

	public List<McpSyncClient> getMcpSyncClients(AgentConfig config) {
		if (config == null || config.getMcpServers() == null || config.getMcpServers().isEmpty()) {
			return List.of();
		}
		List<McpSyncClient> syncClients = new ArrayList<>();
		for (Map.Entry<String, McpConfigSpec.McpServerConfig> entry : config.getMcpServers().entrySet()) {
			McpSyncClient mcpSyncClient = createMcpSyncClient(config.getAgentId(), entry.getKey(), entry.getValue());
			syncClients.add(mcpSyncClient);
		}

		return syncClients;
	}

	public McpSyncClient createMcpSyncClient(String agentId, String serverName, McpConfigSpec.McpServerConfig serverConfig) {
		McpClientTransport transport;

		HttpRequest.Builder httpRequestBuilder = HttpRequest.newBuilder();
		if (serverConfig.headers() != null) {
			serverConfig.headers().forEach(httpRequestBuilder::header);
		}
		// 针对 AgentRun，根据 agentId 创建或连接独占的沙箱
		// x-agentrun-session-id 对特殊符号的使用似乎存在问题
		httpRequestBuilder.header("x-agentrun-session-id",
				agentId.replaceAll("[^a-zA-Z0-9-]+", "-"));

		switch (serverConfig.type().toLowerCase()) {
			case "streamable-http" -> {
				transport = HttpClientStreamableHttpTransport.builder(serverConfig.url())
						.endpoint("/")
						.requestBuilder(httpRequestBuilder)
						.build();
			}
			case "sse" -> {
				transport = HttpClientSseClientTransport.builder(serverConfig.url())
						.sseEndpoint("/")
						.requestBuilder(httpRequestBuilder)
						.build();
			}
			default -> throw new IllegalArgumentException(
					"Unsupported transport type: " + serverConfig.type() + " (server: " + serverName + ")"
			);
		}

		McpSyncClient mcpSyncClient = McpClient.sync(transport)
				.requestTimeout(Duration.ofSeconds(MCP_REQUEST_TO_IN_SEC))
//				.capabilities(McpSchema.ClientCapabilities.builder()
//						.roots(false)
//						.build())
				.build();

		retryInitialize(mcpSyncClient);
		log.info("Mcp Client [" + serverName + "] created and initialized");
		return mcpSyncClient;
	}

	private void retryInitialize(McpSyncClient mcpSyncClient) {
		for (int i = 1; i <= MAX_INIT_RETRY_COUNT; i++) {
			try {
				mcpSyncClient.initialize();
				return;
			} catch (RuntimeException e) {
				if (e.getMessage() == null || !e.getMessage().contains(ERR_MSG_CAN_RETRY)) {
					log.error(e.getMessage(), e);
					throw e;
				}
				log.info("Retrying to initialize mcp client: " + i + "/" + MAX_INIT_RETRY_COUNT);
			}
		}
		throw new RuntimeException("Failed to initialize mcp client after " + MAX_INIT_RETRY_COUNT + " retries");
	}

	public void closeMcpSyncClients(List<McpSyncClient> mcpSyncClients) {
		for (McpSyncClient client : mcpSyncClients) {
//			client.closeGracefully();
			client.close();
		}
	}

}
