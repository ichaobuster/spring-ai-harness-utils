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
	 * 使用 OSS 与 JUMP OSS 的 StorageProvider 时，需要指定 bucketName
	 */
	private String ossBucket;

	/**
	 * 工作空间路径
	 */
	private String pwd = "workspace/";

	/**
	 * 管理员 API 认证 Token
	 */
	private String adminToken = "admin-secret";

	private ObservabilityProperties observability = new ObservabilityProperties();

	@Data
	public static class ObservabilityProperties {
		/**
		 * Whether to enable OpenTelemetry observability tracing.
		 */
		private boolean enabled = false;

		/**
		 * Exporter type: "otlp", "none"
		 */
		private String exportType = "otlp";

		/**
		 * Tracing sampling probability.
		 */
		private double probability = 1.0;
	}

}
