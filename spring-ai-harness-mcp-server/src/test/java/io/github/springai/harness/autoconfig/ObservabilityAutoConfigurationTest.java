package io.github.springai.harness.autoconfig;

import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.aliyun.oss.OSS;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.Mockito;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ObservabilityAutoConfiguration}.
 */
@DisplayName("ObservabilityAutoConfiguration Unit Tests")
class ObservabilityAutoConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(
					HarnessMcpServerAutoConfiguration.class,
					ObservabilityAutoConfiguration.class
			))
			.withUserConfiguration(MockDependenciesConfiguration.class);

	@Configuration
	static class MockDependenciesConfiguration {
		@Bean
		public OSS ossClient() {
			return Mockito.mock(OSS.class);
		}

		@Bean(name = "mcpServerObjectMapper")
		public ObjectMapper mcpServerObjectMapper() {
			return Mockito.mock(ObjectMapper.class);
		}

		@Bean
		public McpServerStreamableHttpProperties mcpServerStreamableHttpProperties() {
			return new McpServerStreamableHttpProperties();
		}
	}

	@Test
	@DisplayName("Should not configure observability beans when disabled")
	void shouldNotConfigureObservabilityWhenDisabled() {
		this.contextRunner
				.withPropertyValues("spring.ai.harness.mcp.server.observability.enabled=false")
				.run(context -> {
					assertThat(context).doesNotHaveBean(Sampler.class);
					assertThat(context).doesNotHaveBean(Resource.class);
					assertThat(context).doesNotHaveBean(SpanExporter.class);
				});
	}

	@Test
	@DisplayName("Should configure observability beans when enabled with default settings")
	void shouldConfigureObservabilityWithDefaultSettings() {
		this.contextRunner
				.withPropertyValues(
						"spring.ai.harness.mcp.server.observability.enabled=true"
				)
				.run(context -> {
					assertThat(context).hasSingleBean(Sampler.class);
					assertThat(context).hasSingleBean(Resource.class);
					assertThat(context).hasSingleBean(SpanExporter.class);
				});
	}

	@Test
	@DisplayName("Should configure observability beans when enabled with otlp export")
	void shouldConfigureObservabilityWithOtlp() {
		this.contextRunner
				.withPropertyValues(
						"spring.ai.harness.mcp.server.observability.enabled=true",
						"spring.ai.harness.mcp.server.observability.export-type=otlp"
				)
				.run(context -> {
					assertThat(context).hasSingleBean(Sampler.class);
					assertThat(context).hasSingleBean(Resource.class);
					assertThat(context).hasSingleBean(SpanExporter.class);
				});
	}

	@Test
	@DisplayName("Should configure observability beans when enabled with none export")
	void shouldConfigureObservabilityWithNone() {
		this.contextRunner
				.withPropertyValues(
						"spring.ai.harness.mcp.server.observability.enabled=true",
						"spring.ai.harness.mcp.server.observability.export-type=none"
				)
				.run(context -> {
					assertThat(context).hasSingleBean(Sampler.class);
					assertThat(context).hasSingleBean(Resource.class);
					assertThat(context).hasSingleBean(SpanExporter.class);
				});
	}
}
