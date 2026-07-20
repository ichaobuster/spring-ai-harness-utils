package io.github.springai.harness.toolgateway.service;

import io.github.springai.harness.toolgateway.catalog.HttpEndpointConfig;
import io.github.springai.harness.toolgateway.catalog.ToolDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolInvocationServiceTest {

	@Mock
	private ToolCatalogService catalogService;

	@Mock
	private RestClient restClient;

	@Mock
	private RestClient.RequestBodyUriSpec requestBodyUriSpec;

	@Mock
	private RestClient.RequestBodySpec requestBodySpec;

	@Mock
	private RestClient.ResponseSpec responseSpec;

	private ToolInvocationService invocationService;
	private ToolDefinition weatherTool;
	private Map<String, String> sampleHeaders;

	@BeforeEach
	void setUp() {
		invocationService = new ToolInvocationService(catalogService, restClient);
		sampleHeaders = Map.of("Authorization", "Bearer test-token", "X-User-Id", "user123");

		HttpEndpointConfig endpointConfig = new HttpEndpointConfig(
				"https://api.example.com/weather?city={city}",
				"GET",
				Map.of("X-Test", "Value"),
				"application/json",
				10,
				true
		);

		weatherTool = new ToolDefinition(
				"weather_query",
				"query weather",
				Map.of("city", "string"),
				null,
				null,
				endpointConfig,
				Collections.emptyList(),
				true
		);
	}

	@Test
	@SuppressWarnings("unchecked")
	void testInvokeToolSuccess() {
		when(catalogService.findTool("weather_query", sampleHeaders))
				.thenReturn(Optional.of(weatherTool));

		when(restClient.method(HttpMethod.GET)).thenReturn(requestBodyUriSpec);
		when(requestBodyUriSpec.uri(anyString(), anyMap())).thenReturn(requestBodySpec);
		when(requestBodySpec.header(anyString(), anyString())).thenReturn(requestBodySpec);
		when(requestBodySpec.retrieve()).thenReturn(responseSpec);
		when(responseSpec.toEntity(String.class)).thenReturn(new ResponseEntity<>("{\"temp\":\"20\"}", HttpStatus.OK));

		Map<String, Object> result = invocationService.invokeTool(
				"weather_query",
				Map.of("city", "Shanghai"),
				sampleHeaders
		);

		assertThat(result.get("isError")).isEqualTo(false);
		List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
		assertThat(content).hasSize(1);
		assertThat(content.get(0).get("text")).isEqualTo("{\"temp\":\"20\"}");

		verify(requestBodySpec).header("X-Test", "Value");
		verify(requestBodySpec).header("Authorization", "Bearer test-token");
		verify(requestBodySpec).header("X-User-Id", "user123");
	}

	@Test
	void testInvokeToolNotFound() {
		when(catalogService.findTool("invalid_tool", sampleHeaders))
				.thenReturn(Optional.empty());

		Map<String, Object> result = invocationService.invokeTool(
				"invalid_tool",
				Map.of("city", "Shanghai"),
				sampleHeaders
		);

		assertThat(result.get("isError")).isEqualTo(true);
	}

	@Test
	void testInvokeToolNoEndpoint() {
		ToolDefinition toolNoEndpoint = new ToolDefinition(
				"no_endpoint", "desc", null, null, null, null, null, true
		);
		when(catalogService.findTool("no_endpoint", sampleHeaders))
				.thenReturn(Optional.of(toolNoEndpoint));

		Map<String, Object> result = invocationService.invokeTool(
				"no_endpoint",
				Map.of(),
				sampleHeaders
		);

		assertThat(result.get("isError")).isEqualTo(true);
	}

	@Test
	void testInvokeToolHttpError() {
		when(catalogService.findTool("weather_query", sampleHeaders))
				.thenReturn(Optional.of(weatherTool));

		when(restClient.method(HttpMethod.GET)).thenReturn(requestBodyUriSpec);
		when(requestBodyUriSpec.uri(anyString(), anyMap())).thenReturn(requestBodySpec);
		when(requestBodySpec.header(anyString(), anyString())).thenReturn(requestBodySpec);
		when(requestBodySpec.retrieve()).thenReturn(responseSpec);
		
		when(responseSpec.toEntity(String.class))
				.thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND, "Not Found", "{\"error\":\"not_found\"}".getBytes(), null));

		Map<String, Object> result = invocationService.invokeTool(
				"weather_query",
				Map.of("city", "Shanghai"),
				sampleHeaders
		);

		assertThat(result.get("isError")).isEqualTo(true);
		List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
		assertThat(content.get(0).get("text").toString()).contains("404");
	}

	@Test
	void testInvokeToolGenericException() {
		when(catalogService.findTool("weather_query", sampleHeaders))
				.thenReturn(Optional.of(weatherTool));

		when(restClient.method(HttpMethod.GET)).thenReturn(requestBodyUriSpec);
		when(requestBodyUriSpec.uri(anyString(), anyMap())).thenReturn(requestBodySpec);
		when(requestBodySpec.header(anyString(), anyString())).thenReturn(requestBodySpec);
		when(requestBodySpec.retrieve()).thenReturn(responseSpec);
		
		when(responseSpec.toEntity(String.class))
				.thenThrow(new RuntimeException("Connection timeout"));

		Map<String, Object> result = invocationService.invokeTool(
				"weather_query",
				Map.of("city", "Shanghai"),
				sampleHeaders
		);

		assertThat(result.get("isError")).isEqualTo(true);
		List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
		assertThat(content.get(0).get("text").toString()).contains("Connection timeout");
	}
}
