package io.github.springai.harness.autoconfig;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Spring AI Harness MCP Server.
 */
@Data
@ConfigurationProperties(prefix = HarnessMcpServerProperties.CONFIG_PREFIX)
public class HarnessMcpServerProperties {

	/**
	 * Spring AI Bocom Harness Agents configuration prefix.
	 */
	public static final String CONFIG_PREFIX = "spring.ai.harness.mcp.server";

	/**
	 * OSS 固定前缀
	 */
	private String ossPrefix = "mcp/workspaces/";

	/**
	 * 使用 OSS 与 JUMP OSS 的 StroageProvider 时，需要指定 bucketName
	 */
	private String ossBucket;

	/**
	 * 工作空间路径
	 */
	private String pwd = "workspace/";

	/**
	 * 公共 Skills 存储前缀
	 */
	private String globalSkillsPrefix = "mcp/global/skills/";

}
