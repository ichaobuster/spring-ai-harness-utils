package io.github.springai.harness.toolgateway.autoconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.springai.harness.toolgateway.auth.AllowAllGatewayAuthProvider;
import io.github.springai.harness.toolgateway.auth.GatewayAuthProvider;
import io.github.springai.harness.toolgateway.catalog.JsonResourceToolCatalogProvider;
import io.github.springai.harness.toolgateway.catalog.ToolCatalogProvider;
import io.github.springai.harness.toolgateway.controller.ToolGatewayMcpController;
import io.github.springai.harness.toolgateway.filter.AllowAllToolPermissionFilter;
import io.github.springai.harness.toolgateway.filter.ToolPermissionFilter;
import io.github.springai.harness.toolgateway.service.ToolCatalogService;
import io.github.springai.harness.toolgateway.service.ToolInvocationService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;
import org.springframework.web.client.RestClient;

/**
 * MCP Tool Gateway 自动配置类。
 * 当 spring.ai.mcp.tool-gateway.enabled 为 true 时自动装配网关所需的所有组件。
 *
 * @author ichaobuster
 */
@AutoConfiguration
@EnableConfigurationProperties(ToolGatewayProperties.class)
@ConditionalOnProperty(prefix = "spring.ai.mcp.tool-gateway", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ToolGatewayAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public GatewayAuthProvider gatewayAuthProvider() {
		return new AllowAllGatewayAuthProvider();
	}

	@Bean
	@ConditionalOnMissingBean
	public ToolCatalogProvider toolCatalogProvider(
			ToolGatewayProperties properties,
			ObjectMapper objectMapper,
			ResourceLoader resourceLoader) {
		return new JsonResourceToolCatalogProvider(properties.getCatalogPath(), objectMapper, resourceLoader);
	}

	@Bean
	@ConditionalOnMissingBean
	public ToolPermissionFilter toolPermissionFilter() {
		return new AllowAllToolPermissionFilter();
	}

	@Bean
	@ConditionalOnMissingBean
	public ToolCatalogService toolCatalogService(
			ToolCatalogProvider catalogProvider,
			ToolPermissionFilter permissionFilter) {
		return new ToolCatalogService(catalogProvider, permissionFilter);
	}

	@Bean(name = "toolGatewayRestClient")
	@ConditionalOnMissingBean(name = "toolGatewayRestClient")
	public RestClient toolGatewayRestClient() {
		return RestClient.create();
	}

	@Bean
	@ConditionalOnMissingBean
	public ToolInvocationService toolInvocationService(
			ToolCatalogService catalogService,
			@Qualifier("toolGatewayRestClient") RestClient restClient) {
		return new ToolInvocationService(catalogService, restClient);
	}

	@Bean
	@ConditionalOnMissingBean
	public ToolGatewayMcpController toolGatewayMcpController(
			ToolCatalogService catalogService,
			ToolInvocationService invocationService,
			ToolGatewayProperties properties,
			GatewayAuthProvider authProvider) {
		return new ToolGatewayMcpController(catalogService, invocationService, properties, authProvider);
	}
}
