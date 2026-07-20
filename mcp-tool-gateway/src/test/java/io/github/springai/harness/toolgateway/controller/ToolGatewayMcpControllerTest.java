package io.github.springai.harness.toolgateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.springai.harness.toolgateway.auth.AllowAllGatewayAuthProvider;
import io.github.springai.harness.toolgateway.auth.GatewayAuthProvider;
import io.github.springai.harness.toolgateway.auth.GatewayAuthenticationException;
import io.github.springai.harness.toolgateway.autoconfig.ToolGatewayProperties;
import io.github.springai.harness.toolgateway.service.ToolCatalogService;
import io.github.springai.harness.toolgateway.service.ToolInvocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ToolGatewayMcpControllerTest {

	@Mock
	private ToolCatalogService catalogService;

	@Mock
	private ToolInvocationService invocationService;

	@Mock
	private GatewayAuthProvider customAuthProvider;

	private ToolGatewayProperties properties;
	private GatewayAuthProvider defaultAuthProvider;
	private ToolGatewayMcpController controller;
	private MockMvc mockMvc;
	private ObjectMapper objectMapper;

	@BeforeEach
	void setUp() {
		properties = new ToolGatewayProperties();
		properties.setServerName("mcp-tool-gateway-test");
		properties.setServerVersion("2.0.0");
		properties.setMcpEndpoint("/mcp");
		properties.setForwardHeaders(List.of("Authorization", "X-Custom-Header"));

		defaultAuthProvider = new AllowAllGatewayAuthProvider();
		controller = new ToolGatewayMcpController(catalogService, invocationService, properties, defaultAuthProvider);
		mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
		objectMapper = new ObjectMapper();
	}

	@Test
	void testInitialize() throws Exception {
		Map<String, Object> request = Map.of(
				"jsonrpc", "2.0",
				"id", 1,
				"method", "initialize",
				"params", Map.of()
		);

		mockMvc.perform(post("/mcp")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.jsonrpc", is("2.0")))
				.andExpect(jsonPath("$.id", is(1)))
				.andExpect(jsonPath("$.result.protocolVersion", is("2025-03-26")))
				.andExpect(jsonPath("$.result.serverInfo.name", is("mcp-tool-gateway-test")))
				.andExpect(jsonPath("$.result.serverInfo.version", is("2.0.0")));
	}

	@Test
	void testInitializedNotification() throws Exception {
		Map<String, Object> request = Map.of(
				"jsonrpc", "2.0",
				"method", "notifications/initialized"
		);

		mockMvc.perform(post("/mcp")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isNoContent());
	}

	@Test
	void testToolsList() throws Exception {
		Map<String, String> expectedHeaders = Map.of("Authorization", "Bearer my-token", "X-Custom-Header", "custom-val");
		when(catalogService.listTools(expectedHeaders)).thenReturn(List.of(
				Map.of("name", "tool1", "description", "desc1")
		));

		Map<String, Object> request = Map.of(
				"jsonrpc", "2.0",
				"id", 2,
				"method", "tools/list",
				"params", Map.of()
		);

		mockMvc.perform(post("/mcp")
						.header("Authorization", "Bearer my-token")
						.header("X-Custom-Header", "custom-val")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id", is(2)))
				.andExpect(jsonPath("$.result.tools[0].name", is("tool1")));
	}

	@Test
	void testToolsCall() throws Exception {
		Map<String, String> expectedHeaders = Map.of("Authorization", "Bearer my-token");
		when(invocationService.invokeTool(eq("weather_query"), anyMap(), eq(expectedHeaders)))
				.thenReturn(Map.of("isError", false, "content", List.of(Map.of("type", "text", "text", "nice weather"))));

		Map<String, Object> request = Map.of(
				"jsonrpc", "2.0",
				"id", 3,
				"method", "tools/call",
				"params", Map.of(
						"name", "weather_query",
						"arguments", Map.of("city", "Beijing")
				)
		);

		mockMvc.perform(post("/mcp")
						.header("Authorization", "Bearer my-token")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id", is(3)))
				.andExpect(jsonPath("$.result.isError", is(false)))
				.andExpect(jsonPath("$.result.content[0].text", is("nice weather")));
	}

	@Test
	void testAuthenticationFailureReturns401() throws Exception {
		ToolGatewayMcpController authFailController = new ToolGatewayMcpController(
				catalogService, invocationService, properties, customAuthProvider);
		MockMvc authFailMockMvc = MockMvcBuilders.standaloneSetup(authFailController).build();

		doThrow(new GatewayAuthenticationException("Invalid token"))
				.when(customAuthProvider).authenticate(anyMap());

		Map<String, Object> request = Map.of(
				"jsonrpc", "2.0",
				"id", 99,
				"method", "tools/list",
				"params", Map.of()
		);

		authFailMockMvc.perform(post("/mcp")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.id", is(99)))
				.andExpect(jsonPath("$.error.code", is(-32001)))
				.andExpect(jsonPath("$.error.message", containsString("Authentication failed: Invalid token")));
	}

	@Test
	void testToolsCallInvalidParams() throws Exception {
		Map<String, Object> request = Map.of(
				"jsonrpc", "2.0",
				"id", 4,
				"method", "tools/call"
		);

		mockMvc.perform(post("/mcp")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code", is(-32602)))
				.andExpect(jsonPath("$.error.message", containsString("params object is missing")));
	}

	@Test
	void testMethodNotFound() throws Exception {
		Map<String, Object> request = Map.of(
				"jsonrpc", "2.0",
				"id", 5,
				"method", "invalid_method",
				"params", Map.of()
		);

		mockMvc.perform(post("/mcp")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code", is(-32601)))
				.andExpect(jsonPath("$.error.message", containsString("Method not found")));
	}
}
