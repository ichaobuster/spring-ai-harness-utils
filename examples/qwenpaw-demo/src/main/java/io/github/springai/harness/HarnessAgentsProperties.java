package io.github.springai.harness;

import io.github.springai.harness.mcp.McpConfigSpec;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Configuration properties for BocomAI Harness Agents.
 */
@Data
@ConfigurationProperties(prefix = HarnessAgentsProperties.CONFIG_PREFIX)
public class HarnessAgentsProperties {

	/**
	 * Spring AI Bocom Harness Agents configuration prefix.
	 */
	public static final String CONFIG_PREFIX = "spring.ai.bocom.harness.agents";

	/**
	 * workspace 本地文件系统路径
	 */
	private String workspaceDir = "/workspace";

	/**
	 * StroageProvider 类型，本地开发使用 local，其他环境使用 oss 或不填写
	 */
	private String storageProvider;

	/**
	 * 使用 OSS 与 JUMP OSS 的 StroageProvider 时，需要指定 bucketName
	 */
	private String ossBucket;

	@NestedConfigurationProperty
	private McpConfigSpec.McpServerConfig sandboxMcp;

}
