package io.github.springai.harness.toolgateway.autoconfig;

import io.github.springai.harness.toolgateway.auth.GatewayAuthProvider;
import io.github.springai.harness.toolgateway.catalog.ToolCatalogProvider;
import io.github.springai.harness.toolgateway.controller.ToolGatewayMcpController;
import io.github.springai.harness.toolgateway.filter.ToolPermissionFilter;
import io.github.springai.harness.toolgateway.service.ToolCatalogService;
import io.github.springai.harness.toolgateway.service.ToolInvocationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolGatewayAutoConfiguration Unit Tests")
class ToolGatewayAutoConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(
					JacksonAutoConfiguration.class,
					ToolGatewayAutoConfiguration.class
			));

	@Test
	@DisplayName("Should configure all gateway beans by default")
	void shouldConfigureDefaultBeans() {
		this.contextRunner.run(context -> {
			assertThat(context).hasSingleBean(ToolGatewayProperties.class);
			assertThat(context).hasSingleBean(GatewayAuthProvider.class);
			assertThat(context).hasSingleBean(ToolCatalogProvider.class);
			assertThat(context).hasSingleBean(ToolPermissionFilter.class);
			assertThat(context).hasSingleBean(ToolCatalogService.class);
			assertThat(context).hasSingleBean(ToolInvocationService.class);
			assertThat(context).hasSingleBean(ToolGatewayMcpController.class);
			assertThat(context).hasBean("toolGatewayRestClient");
		});
	}

	@Test
	@DisplayName("Should not configure any beans when disabled")
	void shouldNotConfigureBeansWhenDisabled() {
		this.contextRunner
				.withPropertyValues("spring.ai.mcp.tool-gateway.enabled=false")
				.run(context -> {
					assertThat(context).doesNotHaveBean(ToolGatewayProperties.class);
					assertThat(context).doesNotHaveBean(GatewayAuthProvider.class);
					assertThat(context).doesNotHaveBean(ToolCatalogProvider.class);
					assertThat(context).doesNotHaveBean(ToolPermissionFilter.class);
					assertThat(context).doesNotHaveBean(ToolCatalogService.class);
					assertThat(context).doesNotHaveBean(ToolInvocationService.class);
					assertThat(context).doesNotHaveBean(ToolGatewayMcpController.class);
				});
	}

	@Test
	@DisplayName("Should respect custom properties values")
	void shouldRespectCustomProperties() {
		this.contextRunner
				.withPropertyValues(
						"spring.ai.mcp.tool-gateway.catalog-path=classpath:custom-catalog.json",
						"spring.ai.mcp.tool-gateway.mcp-endpoint=/custom-mcp",
						"spring.ai.mcp.tool-gateway.server-name=custom-name",
						"spring.ai.mcp.tool-gateway.server-version=9.9.9",
						"spring.ai.mcp.tool-gateway.forward-headers[0]=Authorization",
						"spring.ai.mcp.tool-gateway.forward-headers[1]=X-Tenant-Id"
				)
				.run(context -> {
					ToolGatewayProperties properties = context.getBean(ToolGatewayProperties.class);
					assertThat(properties.getCatalogPath()).isEqualTo("classpath:custom-catalog.json");
					assertThat(properties.getMcpEndpoint()).isEqualTo("/custom-mcp");
					assertThat(properties.getServerName()).isEqualTo("custom-name");
					assertThat(properties.getServerVersion()).isEqualTo("9.9.9");
					assertThat(properties.getForwardHeaders()).containsExactly("Authorization", "X-Tenant-Id");
				});
	}
}
