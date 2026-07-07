package io.github.springai.harness.autoconfig;

import com.aliyun.oss.OSS;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AliyunOssAutoConfiguration}.
 */
@DisplayName("AliyunOssAutoConfiguration Unit Tests")
class AliyunOssAutoConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(AliyunOssAutoConfiguration.class));

	@Test
	@DisplayName("Should configure Aliyun OSS client with properties")
	void shouldConfigureOssClient() {
		this.contextRunner
				.withPropertyValues(
						"aliyun.oss.endpoint=http://oss-cn-hangzhou.aliyuncs.com",
						"aliyun.oss.access-key-id=test-id",
						"aliyun.oss.access-key-secret=test-secret"
				)
				.run(context -> {
					assertThat(context).hasSingleBean(OSS.class);
					assertThat(context).hasSingleBean(AliyunOssProperties.class);
				});
	}
}
