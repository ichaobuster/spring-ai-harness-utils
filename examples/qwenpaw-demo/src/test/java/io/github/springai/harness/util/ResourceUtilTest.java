package io.github.springai.harness.util;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceUtilTest {

	@Test
	void loadResourceAsString() {
		String result = ResourceUtil.loadResourceAsString(new ClassPathResource("prompt/TEST.md"));
		assertThat(result).isEqualTo("This is a test resource.");
	}

	@Test
	void loadResourceAsStringWithError() {
		assertThatThrownBy(() -> ResourceUtil.loadResourceAsString(new ClassPathResource("prompt/NOT_EXISTS.md")))
				.isInstanceOf(RuntimeException.class);
	}
}