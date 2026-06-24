package io.github.springai.harness.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryUtilTest {

	@Test
	void getDefaultAutoMemoryToolsSystemPrompt() {
		String result = MemoryUtil.getDefaultAutoMemoryToolsSystemPrompt();
		assertThat(result).isNotEmpty();

	}
}