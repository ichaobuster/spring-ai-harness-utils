package io.github.springai.harness.tool;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Tools Proxy Relay Component.
 * Forward MCP tool calls to the downstream streamable-http MCP server.
 *
 * @author Antigravity
 */
@Component
@ConditionalOnProperty(prefix = "spring.ai.harness.mcp.server.relay", name = "enabled", havingValue = "true")
@Slf4j
public class RelayTools {

	@Autowired
	private RelayMcpClientManager relayMcpClientManager;

	private CallToolResult relayCall(McpTransportContext context, String toolName, Map<String, Object> arguments) {
		ServerRequest serverRequest = (ServerRequest) context.get(McpTransportContext.KEY);
		if (serverRequest == null) {
			log.error("ServerRequest not found in McpTransportContext during relay call: {}", toolName);
			return CallToolResult.builder()
					.isError(true)
					.addTextContent("Error: McpTransportContext does not contain ServerRequest")
					.build();
		}
		String authHeader = serverRequest.headers().firstHeader("Authorization");
		if (authHeader == null || authHeader.isBlank()) {
			log.error("Authorization header is missing in incoming request during relay call: {}", toolName);
			return CallToolResult.builder()
					.isError(true)
					.addTextContent("Error: Authorization header is missing")
					.build();
		}
		try (McpSyncClient client = relayMcpClientManager.createClient(authHeader)) {
			return client.callTool(new CallToolRequest(toolName, arguments));
		} catch (Exception e) {
			log.error("Failed to relay tool call to downstream MCP server: " + toolName, e);
			return CallToolResult.builder()
					.isError(true)
					.addTextContent("Error: Failed to execute tool downstream: " + e.getMessage())
					.build();
		}
	}

	@McpTool(name = "computer", description = """
		Use a mouse and keyboard to interact with a computer, and take screenshots.
		* This is an interface to a desktop GUI. You do not have access to a terminal or applications menu. You must click on desktop icons to start applications.
		* Always prefer using keyboard shortcuts rather than clicking, where possible.
		* If you see boxes with two letters in them, typing these letters will click that element. Use this instead of other shortcuts or clicking, where possible.
		* Some applications may take time to start or process actions, so you may need to wait and take successive screenshots to see the results of your actions. E.g. if you click on Firefox and a window doesn't open, try taking another screenshot.
		* Whenever you intend to move the cursor to click on an element like an icon, you should consult a screenshot to determine the coordinates of the element before moving the cursor.
		* If you tried clicking on a program or link but it failed to load, even after waiting, try adjusting your cursor position so that the tip of the cursor visually falls on the element that you want to click.
		* Make sure to click any buttons, links, icons, etc with the cursor tip in the center of the element. Don't click boxes on their edges unless asked.
		""", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
	public CallToolResult computer(
			McpTransportContext context,
			String action,
			@McpToolParam(required = false) List<Integer> coordinate,
			@McpToolParam(required = false) String text) {
		Map<String, Object> args = new HashMap<>();
		args.put("action", action);
		args.put("coordinate", coordinate);
		args.put("text", text);
		return relayCall(context, "computer", args);
	}

	@McpTool(name = "browser_close", description = "Close the page", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true))
	public CallToolResult browserClose(McpTransportContext context) {
		return relayCall(context, "browser_close", Map.of());
	}

	@McpTool(name = "browser_resize", description = "Resize the browser window", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
	public CallToolResult browserResize(
			McpTransportContext context,
			Double width,
			Double height) {
		return relayCall(context, "browser_resize", Map.of("width", width, "height", height));
	}

	@McpTool(name = "browser_console_messages", description = "Returns all console messages", annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
	public CallToolResult browserConsoleMessages(McpTransportContext context) {
		return relayCall(context, "browser_console_messages", Map.of());
	}

	@McpTool(name = "browser_handle_dialog", description = "Handle a dialog", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
	public CallToolResult browserHandleDialog(
			McpTransportContext context,
			Boolean accept,
			@McpToolParam(required = false) String promptText) {
		Map<String, Object> args = new HashMap<>();
		args.put("accept", accept);
		args.put("promptText", promptText);
		return relayCall(context, "browser_handle_dialog", args);
	}

	@McpTool(name = "browser_file_upload", description = "Upload one or multiple files", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
	public CallToolResult browserFileUpload(
			McpTransportContext context,
			List<String> paths) {
		return relayCall(context, "browser_file_upload", Map.of("paths", paths));
	}

	@McpTool(name = "browser_press_key", description = "Press a key on the keyboard", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
	public CallToolResult browserPressKey(
			McpTransportContext context,
			String key) {
		return relayCall(context, "browser_press_key", Map.of("key", key));
	}

	@McpTool(name = "browser_navigate", description = "Navigate to a URL", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
	public CallToolResult browserNavigate(
			McpTransportContext context,
			String url) {
		return relayCall(context, "browser_navigate", Map.of("url", url));
	}

	@McpTool(name = "browser_navigate_back", description = "Go back to the previous page", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
	public CallToolResult browserNavigateBack(McpTransportContext context) {
		return relayCall(context, "browser_navigate_back", Map.of());
	}

	@McpTool(name = "browser_navigate_forward", description = "Go forward to the next page", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
	public CallToolResult browserNavigateForward(McpTransportContext context) {
		return relayCall(context, "browser_navigate_forward", Map.of());
	}

	@McpTool(name = "browser_network_requests", description = "Returns all network requests since loading the page", annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
	public CallToolResult browserNetworkRequests(McpTransportContext context) {
		return relayCall(context, "browser_network_requests", Map.of());
	}

	@McpTool(name = "browser_pdf_save", description = "Save page as PDF", annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
	public CallToolResult browserPdfSave(
			McpTransportContext context,
			@McpToolParam(required = false) String filename) {
		Map<String, Object> args = new HashMap<>();
		args.put("filename", filename);
		return relayCall(context, "browser_pdf_save", args);
	}

	@McpTool(name = "browser_take_screenshot", description = "Take a screenshot of the current page. You can't perform actions based on the screenshot, use browser_snapshot for actions.", annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
	public CallToolResult browserTakeScreenshot(
			McpTransportContext context,
			@McpToolParam(required = false) Boolean raw,
			@McpToolParam(required = false) String filename,
			@McpToolParam(required = false) String element,
			@McpToolParam(required = false) String ref) {
		Map<String, Object> args = new HashMap<>();
		args.put("raw", raw);
		args.put("filename", filename);
		args.put("element", element);
		args.put("ref", ref);
		return relayCall(context, "browser_take_screenshot", args);
	}

	@McpTool(name = "browser_snapshot", description = "Capture accessibility snapshot of the current page, this is better than screenshot", annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
	public CallToolResult browserSnapshot(McpTransportContext context) {
		return relayCall(context, "browser_snapshot", Map.of());
	}

	@McpTool(name = "browser_click", description = "Perform click on a web page", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
	public CallToolResult browserClick(
			McpTransportContext context,
			String element,
			String ref) {
		return relayCall(context, "browser_click", Map.of("element", element, "ref", ref));
	}

	@McpTool(name = "browser_drag", description = "Perform drag and drop between two elements", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
	public CallToolResult browserDrag(
			McpTransportContext context,
			String startElement,
			String startRef,
			String endElement,
			String endRef) {
		Map<String, Object> args = new HashMap<>();
		args.put("startElement", startElement);
		args.put("startRef", startRef);
		args.put("endElement", endElement);
		args.put("endRef", endRef);
		return relayCall(context, "browser_drag", args);
	}

	@McpTool(name = "browser_hover", description = "Hover over element on page", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
	public CallToolResult browserHover(
			McpTransportContext context,
			String element,
			String ref) {
		return relayCall(context, "browser_hover", Map.of("element", element, "ref", ref));
	}

	@McpTool(name = "browser_type", description = "Type text into editable element", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
	public CallToolResult browserType(
			McpTransportContext context,
			String element,
			String ref,
			String text,
			@McpToolParam(required = false) Boolean submit,
			@McpToolParam(required = false) Boolean slowly) {
		Map<String, Object> args = new HashMap<>();
		args.put("element", element);
		args.put("ref", ref);
		args.put("text", text);
		args.put("submit", submit);
		args.put("slowly", slowly);
		return relayCall(context, "browser_type", args);
	}

	@McpTool(name = "browser_select_option", description = "Select an option in a dropdown", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
	public CallToolResult browserSelectOption(
			McpTransportContext context,
			String element,
			String ref,
			List<String> values) {
		return relayCall(context, "browser_select_option", Map.of("element", element, "ref", ref, "values", values));
	}

	@McpTool(name = "browser_tab_list", description = "List browser tabs", annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
	public CallToolResult browserTabList(McpTransportContext context) {
		return relayCall(context, "browser_tab_list", Map.of());
	}

	@McpTool(name = "browser_tab_new", description = "Open a new tab", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
	public CallToolResult browserTabNew(
			McpTransportContext context,
			@McpToolParam(required = false) String url) {
		Map<String, Object> args = new HashMap<>();
		args.put("url", url);
		return relayCall(context, "browser_tab_new", args);
	}

	@McpTool(name = "browser_tab_select", description = "Select a tab by index", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
	public CallToolResult browserTabSelect(
			McpTransportContext context,
			Double index) {
		return relayCall(context, "browser_tab_select", Map.of("index", index));
	}

	@McpTool(name = "browser_tab_close", description = "Close a tab", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true))
	public CallToolResult browserTabClose(
			McpTransportContext context,
			@McpToolParam(required = false) Double index) {
		Map<String, Object> args = new HashMap<>();
		args.put("index", index);
		return relayCall(context, "browser_tab_close", args);
	}

	@McpTool(name = "browser_wait_for", description = "Wait for text to appear or disappear or a specified time to pass", annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
	public CallToolResult browserWaitFor(
			McpTransportContext context,
			@McpToolParam(required = false) Double time,
			@McpToolParam(required = false) String text,
			@McpToolParam(required = false) String textGone) {
		Map<String, Object> args = new HashMap<>();
		args.put("time", time);
		args.put("text", text);
		args.put("textGone", textGone);
		return relayCall(context, "browser_wait_for", args);
	}

	@McpTool(name = "run_ipython_cell", description = "Execute code in a stateful IPython (Jupyter) kernel and return stdout/stderr.", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
	public CallToolResult runIpythonCell(
			McpTransportContext context,
			String code) {
		return relayCall(context, "run_ipython_cell", Map.of("code", code));
	}

	@McpTool(name = "run_shell_command", description = "Execute a shell command and return stdout/stderr/returncode.", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true))
	public CallToolResult runShellCommand(
			McpTransportContext context,
			String command) {
		return relayCall(context, "run_shell_command", Map.of("command", command));
	}
}
