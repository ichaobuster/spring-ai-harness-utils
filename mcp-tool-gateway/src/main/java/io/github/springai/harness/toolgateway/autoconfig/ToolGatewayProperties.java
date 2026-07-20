package io.github.springai.harness.toolgateway.autoconfig;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * MCP Tool Gateway 的配置属性。
 *
 * @author ichaobuster
 */
@Data
@ConfigurationProperties(prefix = ToolGatewayProperties.CONFIG_PREFIX)
public class ToolGatewayProperties {

	public static final String CONFIG_PREFIX = "spring.ai.mcp.tool-gateway";

	/** 是否启用 Tool Gateway 功能 */
	private boolean enabled = true;

	/** 工具目录配置文件路径 */
	private String catalogPath = "classpath:tool-catalog.json";

	/** MCP JSON-RPC 端点路径 */
	private String mcpEndpoint = "/mcp";

	/** MCP Server 实例名称 */
	private String serverName = "mcp-tool-gateway";

	/** MCP Server 版本 */
	private String serverVersion = "1.0.0";

	/** 需要从请求中提取并透传的 Header key 列表（不区分大小写） */
	private List<String> forwardHeaders = List.of("Authorization");
}
