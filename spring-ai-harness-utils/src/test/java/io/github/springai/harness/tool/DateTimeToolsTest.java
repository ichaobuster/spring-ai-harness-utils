package io.github.springai.harness.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for {@link DateTimeTools}.
 *
 * @author ichaobuster
 */
@DisplayName("DateTimeTools Tests")
class DateTimeToolsTest {

	private DateTimeTools tools;

	@BeforeEach
	void setUp() {
		this.tools = new DateTimeTools();
	}

	@Test
	@DisplayName("test getCurrentDateTime")
	void getCurrentDateTime() throws IOException {
		var testDateTimeFormatter = DateTimeFormatter
				.ofPattern("yyyy-MM-dd EEE HH:mm:ss")
				.withZone(LocaleContextHolder.getTimeZone().toZoneId());
		LocalDateTime now = LocalDateTime.now();

		LocalDateTime result = LocalDateTime.parse(tools.getCurrentDateTime(), testDateTimeFormatter);
		assertThat(result).isCloseTo(now, within(5, ChronoUnit.SECONDS));
	}


}
