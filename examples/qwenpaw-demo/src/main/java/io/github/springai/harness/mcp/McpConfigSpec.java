package io.github.springai.harness.mcp;

import java.util.Map;

/**
 * McpServerConfigSpec
 *
 * @author ichaobuster
 */
public class McpConfigSpec {

	public record McpServerConfig(String type, String url, Map<String, String> headers) {
	}

}
