package io.github.springai.harness.tool;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.function.ServerRequest;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RelayTools}.
 *
 * @author Antigravity
 */
@DisplayName("RelayTools Tests")
@ExtendWith(MockitoExtension.class)
class RelayToolsTest {

	@Mock
	private RelayMcpClientManager relayMcpClientManager;

	@Mock
	private McpSyncClient mcpSyncClient;

	@Mock
	private ServerRequest serverRequest;

	@InjectMocks
	private RelayTools relayTools;

	private McpTransportContext transportContext;

	@BeforeEach
	void setUp() {
		transportContext = McpTransportContext.create(Map.of(McpTransportContext.KEY, serverRequest));
	}

	private void mockSuccessfulClientCall(String toolName, Map<String, Object> expectedArgs, CallToolResult expectedResult) {
		ServerRequest.Headers headers = mock(ServerRequest.Headers.class);
		when(serverRequest.headers()).thenReturn(headers);
		when(headers.firstHeader("Authorization")).thenReturn("Bearer user-token-123");
		when(relayMcpClientManager.createClient("Bearer user-token-123")).thenReturn(mcpSyncClient);
		when(mcpSyncClient.callTool(argThat(request ->
				request.name().equals(toolName) && mapsAreEqual(request.arguments(), expectedArgs)
		))).thenReturn(expectedResult);
	}

	private boolean mapsAreEqual(Map<String, Object> map1, Map<String, Object> map2) {
		if (map1 == map2) return true;
		if (map1 == null || map2 == null) return false;
		if (map1.size() != map2.size()) return false;
		for (Map.Entry<String, Object> entry : map1.entrySet()) {
			Object val1 = entry.getValue();
			Object val2 = map2.get(entry.getKey());
			if (val1 == null) {
				if (val2 != null || !map2.containsKey(entry.getKey())) return false;
			} else {
				if (!val1.equals(val2)) return false;
			}
		}
		return true;
	}

	@Test
	@DisplayName("Should successfully proxy computer tool call")
	void shouldProxyComputer() {
		CallToolResult expectedResult = CallToolResult.builder().isError(false).addTextContent("ok").build();
		Map<String, Object> expectedArgs = new HashMap<>();
		expectedArgs.put("action", "click");
		expectedArgs.put("coordinate", List.of(10, 20));
		expectedArgs.put("text", "search");
		mockSuccessfulClientCall("computer", expectedArgs, expectedResult);

		CallToolResult result = relayTools.computer(transportContext, "click", List.of(10, 20), "search");

		assertThat(result).isSameAs(expectedResult);
		verify(mcpSyncClient).close();
	}

	@Test
	@DisplayName("Should successfully proxy browser_close tool call")
	void shouldProxyBrowserClose() {
		CallToolResult expectedResult = CallToolResult.builder().isError(false).addTextContent("ok").build();
		mockSuccessfulClientCall("browser_close", Map.of(), expectedResult);

		CallToolResult result = relayTools.browserClose(transportContext);

		assertThat(result).isSameAs(expectedResult);
		verify(mcpSyncClient).close();
	}

	@Test
	@DisplayName("Should successfully proxy browser_resize tool call")
	void shouldProxyBrowserResize() {
		CallToolResult expectedResult = CallToolResult.builder().isError(false).addTextContent("ok").build();
		mockSuccessfulClientCall("browser_resize", Map.of("width", 1024.0, "height", 768.0), expectedResult);

		CallToolResult result = relayTools.browserResize(transportContext, 1024.0, 768.0);

		assertThat(result).isSameAs(expectedResult);
	}

	@Test
	@DisplayName("Should successfully proxy browser_console_messages tool call")
	void shouldProxyBrowserConsoleMessages() {
		CallToolResult expectedResult = CallToolResult.builder().isError(false).addTextContent("ok").build();
		mockSuccessfulClientCall("browser_console_messages", Map.of(), expectedResult);

		CallToolResult result = relayTools.browserConsoleMessages(transportContext);

		assertThat(result).isSameAs(expectedResult);
	}

	@Test
	@DisplayName("Should successfully proxy browser_handle_dialog tool call")
	void shouldProxyBrowserHandleDialog() {
		CallToolResult expectedResult = CallToolResult.builder().isError(false).addTextContent("ok").build();
		Map<String, Object> expectedArgs = new HashMap<>();
		expectedArgs.put("accept", true);
		expectedArgs.put("promptText", "prompt text");
		mockSuccessfulClientCall("browser_handle_dialog", expectedArgs, expectedResult);

		CallToolResult result = relayTools.browserHandleDialog(transportContext, true, "prompt text");

		assertThat(result).isSameAs(expectedResult);
	}

	@Test
	@DisplayName("Should successfully proxy browser_file_upload tool call")
	void shouldProxyBrowserFileUpload() {
		CallToolResult expectedResult = CallToolResult.builder().isError(false).addTextContent("ok").build();
		mockSuccessfulClientCall("browser_file_upload", Map.of("paths", List.of("path1")), expectedResult);

		CallToolResult result = relayTools.browserFileUpload(transportContext, List.of("path1"));

		assertThat(result).isSameAs(expectedResult);
	}

	@Test
	@DisplayName("Should successfully proxy browser_press_key tool call")
	void shouldProxyBrowserPressKey() {
		CallToolResult expectedResult = CallToolResult.builder().isError(false).addTextContent("ok").build();
		mockSuccessfulClientCall("browser_press_key", Map.of("key", "Enter"), expectedResult);

		CallToolResult result = relayTools.browserPressKey(transportContext, "Enter");

		assertThat(result).isSameAs(expectedResult);
	}

	@Test
	@DisplayName("Should successfully proxy browser_navigate tool call")
	void shouldProxyBrowserNavigate() {
		CallToolResult expectedResult = CallToolResult.builder().isError(false).addTextContent("ok").build();
		mockSuccessfulClientCall("browser_navigate", Map.of("url", "http://example.com"), expectedResult);

		CallToolResult result = relayTools.browserNavigate(transportContext, "http://example.com");

		assertThat(result).isSameAs(expectedResult);
	}

	@Test
	@DisplayName("Should successfully proxy browser_navigate_back tool call")
	void shouldProxyBrowserNavigateBack() {
		CallToolResult expectedResult = CallToolResult.builder().isError(false).addTextContent("ok").build();
		mockSuccessfulClientCall("browser_navigate_back", Map.of(), expectedResult);

		CallToolResult result = relayTools.browserNavigateBack(transportContext);

		assertThat(result).isSameAs(expectedResult);
	}

	@Test
	@DisplayName("Should successfully proxy browser_navigate_forward tool call")
	void shouldProxyBrowserNavigateForward() {
		CallToolResult expectedResult = CallToolResult.builder().isError(false).addTextContent("ok").build();
		mockSuccessfulClientCall("browser_navigate_forward", Map.of(), expectedResult);

		CallToolResult result = relayTools.browserNavigateForward(transportContext);

		assertThat(result).isSameAs(expectedResult);
	}

	@Test
	@DisplayName("Should successfully proxy browser_network_requests tool call")
	void shouldProxyBrowserNetworkRequests() {
		CallToolResult expectedResult = CallToolResult.builder().isError(false).addTextContent("ok").build();
		mockSuccessfulClientCall("browser_network_requests", Map.of(), expectedResult);

		CallToolResult result = relayTools.browserNetworkRequests(transportContext);

		assertThat(result).isSameAs(expectedResult);
	}

	@Test
	@DisplayName("Should successfully proxy browser_pdf_save tool call")
	void shouldProxyBrowserPdfSave() {
		CallToolResult expectedResult = CallToolResult.builder().isError(false).addTextContent("ok").build();
		Map<String, Object> expectedArgs = new HashMap<>();
		expectedArgs.put("filename", "output.pdf");
		mockSuccessfulClientCall("browser_pdf_save", expectedArgs, expectedResult);

		CallToolResult result = relayTools.browserPdfSave(transportContext, "output.pdf");

		assertThat(result).isSameAs(expectedResult);
	}

	@Test
	@DisplayName("Should successfully proxy browser_take_screenshot tool call")
	void shouldProxyBrowserTakeScreenshot() {
		CallToolResult expectedResult = CallToolResult.builder().isError(false).addTextContent("ok").build();
		Map<String, Object> expectedArgs = new HashMap<>();
		expectedArgs.put("raw", true);
		expectedArgs.put("filename", "s.png");
		expectedArgs.put("element", "div");
		expectedArgs.put("ref", "ref1");
		mockSuccessfulClientCall("browser_take_screenshot", expectedArgs, expectedResult);

		CallToolResult result = relayTools.browserTakeScreenshot(transportContext, true, "s.png", "div", "ref1");

		assertThat(result).isSameAs(expectedResult);
	}

	@Test
	@DisplayName("Should successfully proxy browser_snapshot tool call")
	void shouldProxyBrowserSnapshot() {
		CallToolResult expectedResult = CallToolResult.builder().isError(false).addTextContent("ok").build();
		mockSuccessfulClientCall("browser_snapshot", Map.of(), expectedResult);

		CallToolResult result = relayTools.browserSnapshot(transportContext);

		assertThat(result).isSameAs(expectedResult);
	}

	@Test
	@DisplayName("Should successfully proxy browser_click tool call")
	void shouldProxyBrowserClick() {
		CallToolResult expectedResult = CallToolResult.builder().isError(false).addTextContent("ok").build();
		mockSuccessfulClientCall("browser_click", Map.of("element", "btn", "ref", "ref1"), expectedResult);

		CallToolResult result = relayTools.browserClick(transportContext, "btn", "ref1");

		assertThat(result).isSameAs(expectedResult);
	}

	@Test
	@DisplayName("Should successfully proxy browser_drag tool call")
	void shouldProxyBrowserDrag() {
		CallToolResult expectedResult = CallToolResult.builder().isError(false).addTextContent("ok").build();
		Map<String, Object> expectedArgs = new HashMap<>();
		expectedArgs.put("startElement", "s");
		expectedArgs.put("startRef", "sr");
		expectedArgs.put("endElement", "e");
		expectedArgs.put("endRef", "er");
		mockSuccessfulClientCall("browser_drag", expectedArgs, expectedResult);

		CallToolResult result = relayTools.browserDrag(transportContext, "s", "sr", "e", "er");

		assertThat(result).isSameAs(expectedResult);
	}

	@Test
	@DisplayName("Should successfully proxy browser_hover tool call")
	void shouldProxyBrowserHover() {
		CallToolResult expectedResult = CallToolResult.builder().isError(false).addTextContent("ok").build();
		mockSuccessfulClientCall("browser_hover", Map.of("element", "el", "ref", "ref1"), expectedResult);

		CallToolResult result = relayTools.browserHover(transportContext, "el", "ref1");

		assertThat(result).isSameAs(expectedResult);
	}

	@Test
	@DisplayName("Should successfully proxy browser_type tool call")
	void shouldProxyBrowserType() {
		CallToolResult expectedResult = CallToolResult.builder().isError(false).addTextContent("ok").build();
		Map<String, Object> expectedArgs = new HashMap<>();
		expectedArgs.put("element", "inp");
		expectedArgs.put("ref", "ref1");
		expectedArgs.put("text", "hello");
		expectedArgs.put("submit", true);
		expectedArgs.put("slowly", false);
		mockSuccessfulClientCall("browser_type", expectedArgs, expectedResult);

		CallToolResult result = relayTools.browserType(transportContext, "inp", "ref1", "hello", true, false);

		assertThat(result).isSameAs(expectedResult);
	}

	@Test
	@DisplayName("Should successfully proxy browser_select_option tool call")
	void shouldProxyBrowserSelectOption() {
		CallToolResult expectedResult = CallToolResult.builder().isError(false).addTextContent("ok").build();
		mockSuccessfulClientCall("browser_select_option", Map.of("element", "sel", "ref", "ref1", "values", List.of("val1")), expectedResult);

		CallToolResult result = relayTools.browserSelectOption(transportContext, "sel", "ref1", List.of("val1"));

		assertThat(result).isSameAs(expectedResult);
	}

	@Test
	@DisplayName("Should successfully proxy browser_tab_list tool call")
	void shouldProxyBrowserTabList() {
		CallToolResult expectedResult = CallToolResult.builder().isError(false).addTextContent("ok").build();
		mockSuccessfulClientCall("browser_tab_list", Map.of(), expectedResult);

		CallToolResult result = relayTools.browserTabList(transportContext);

		assertThat(result).isSameAs(expectedResult);
	}

	@Test
	@DisplayName("Should successfully proxy browser_tab_new tool call")
	void shouldProxyBrowserTabNew() {
		CallToolResult expectedResult = CallToolResult.builder().isError(false).addTextContent("ok").build();
		Map<String, Object> expectedArgs = new HashMap<>();
		expectedArgs.put("url", "http://example.com");
		mockSuccessfulClientCall("browser_tab_new", expectedArgs, expectedResult);

		CallToolResult result = relayTools.browserTabNew(transportContext, "http://example.com");

		assertThat(result).isSameAs(expectedResult);
	}

	@Test
	@DisplayName("Should successfully proxy browser_tab_select tool call")
	void shouldProxyBrowserTabSelect() {
		CallToolResult expectedResult = CallToolResult.builder().isError(false).addTextContent("ok").build();
		mockSuccessfulClientCall("browser_tab_select", Map.of("index", 2.0), expectedResult);

		CallToolResult result = relayTools.browserTabSelect(transportContext, 2.0);

		assertThat(result).isSameAs(expectedResult);
	}

	@Test
	@DisplayName("Should successfully proxy browser_tab_close tool call")
	void shouldProxyBrowserTabClose() {
		CallToolResult expectedResult = CallToolResult.builder().isError(false).addTextContent("ok").build();
		Map<String, Object> expectedArgs = new HashMap<>();
		expectedArgs.put("index", 3.0);
		mockSuccessfulClientCall("browser_tab_close", expectedArgs, expectedResult);

		CallToolResult result = relayTools.browserTabClose(transportContext, 3.0);

		assertThat(result).isSameAs(expectedResult);
	}

	@Test
	@DisplayName("Should successfully proxy browser_wait_for tool call")
	void shouldProxyBrowserWaitFor() {
		CallToolResult expectedResult = CallToolResult.builder().isError(false).addTextContent("ok").build();
		Map<String, Object> expectedArgs = new HashMap<>();
		expectedArgs.put("time", 10.0);
		expectedArgs.put("text", "text");
		expectedArgs.put("textGone", "gone");
		mockSuccessfulClientCall("browser_wait_for", expectedArgs, expectedResult);

		CallToolResult result = relayTools.browserWaitFor(transportContext, 10.0, "text", "gone");

		assertThat(result).isSameAs(expectedResult);
	}

	@Test
	@DisplayName("Should successfully proxy run_ipython_cell tool call")
	void shouldProxyRunIpythonCell() {
		CallToolResult expectedResult = CallToolResult.builder().isError(false).addTextContent("ok").build();
		mockSuccessfulClientCall("run_ipython_cell", Map.of("code", "print(1)"), expectedResult);

		CallToolResult result = relayTools.runIpythonCell(transportContext, "print(1)");

		assertThat(result).isSameAs(expectedResult);
	}

	@Test
	@DisplayName("Should successfully proxy run_shell_command tool call")
	void shouldProxyRunShellCommand() {
		CallToolResult expectedResult = CallToolResult.builder().isError(false).addTextContent("ok").build();
		mockSuccessfulClientCall("run_shell_command", Map.of("command", "ls"), expectedResult);

		CallToolResult result = relayTools.runShellCommand(transportContext, "ls");

		assertThat(result).isSameAs(expectedResult);
	}

	@Test
	@DisplayName("Should return error result if McpTransportContext is missing ServerRequest")
	void shouldReturnErrorWhenServerRequestMissing() {
		McpTransportContext emptyContext = McpTransportContext.create(Collections.emptyMap());

		CallToolResult result = relayTools.browserClose(emptyContext);

		assertThat(result.isError()).isTrue();
		assertThat(result.content()).first()
				.extracting(content -> ((io.modelcontextprotocol.spec.McpSchema.TextContent) content).text())
				.asString()
				.contains("Error: McpTransportContext does not contain ServerRequest");
	}

	@Test
	@DisplayName("Should return error result if Authorization header is missing")
	void shouldReturnErrorWhenAuthHeaderMissing() {
		ServerRequest.Headers headers = mock(ServerRequest.Headers.class);
		when(serverRequest.headers()).thenReturn(headers);
		when(headers.firstHeader("Authorization")).thenReturn(null);

		CallToolResult result = relayTools.browserClose(transportContext);

		assertThat(result.isError()).isTrue();
		assertThat(result.content()).first()
				.extracting(content -> ((io.modelcontextprotocol.spec.McpSchema.TextContent) content).text())
				.asString()
				.contains("Error: Authorization header is missing");
	}

	@Test
	@DisplayName("Should return error result if downstream client call fails")
	void shouldReturnErrorWhenDownstreamCallFails() {
		ServerRequest.Headers headers = mock(ServerRequest.Headers.class);
		when(serverRequest.headers()).thenReturn(headers);
		when(headers.firstHeader("Authorization")).thenReturn("Bearer user-token-123");
		when(relayMcpClientManager.createClient("Bearer user-token-123")).thenReturn(mcpSyncClient);
		when(mcpSyncClient.callTool(any(CallToolRequest.class))).thenThrow(new RuntimeException("Connection timeout"));

		CallToolResult result = relayTools.browserClose(transportContext);

		assertThat(result.isError()).isTrue();
		assertThat(result.content()).first()
				.extracting(content -> ((io.modelcontextprotocol.spec.McpSchema.TextContent) content).text())
				.asString()
				.contains("Error: Failed to execute tool downstream: Connection timeout");
		verify(mcpSyncClient).close();
	}
}
