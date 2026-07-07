package io.github.springai.harness.autoconfig;

import com.aliyun.oss.OSS;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.springai.harness.auth.AuthenticationProvider;
import io.github.springai.harness.skill.SkillProvider;
import io.github.springai.harness.snapshot.SnapshotProvider;
import io.github.springai.harness.storage.StorageProviderFactory;
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link HarnessMcpServerAutoConfiguration}.
 */
@DisplayName("HarnessMcpServerAutoConfiguration Unit Tests")
class HarnessMcpServerAutoConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(HarnessMcpServerAutoConfiguration.class))
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
			McpServerStreamableHttpProperties props = new McpServerStreamableHttpProperties();
			// Set minimal properties required if any
			return props;
		}
	}

	@Test
	@DisplayName("Should configure default harness beans successfully")
	void shouldConfigureDefaultHarnessBeans() {
		this.contextRunner.run(context -> {
			assertThat(context).hasSingleBean(HarnessMcpServerProperties.class);
			assertThat(context).hasSingleBean(AuthenticationProvider.class);
			assertThat(context).hasSingleBean(StorageProviderFactory.class);
			assertThat(context).hasSingleBean(SkillProvider.class);
			assertThat(context).hasSingleBean(SnapshotProvider.class);
			assertThat(context).hasSingleBean(WebMvcStatelessServerTransport.class);
		});
	}
}
