package io.github.springai.harness.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.i18n.LocaleContextHolder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * DateTimeTools
 *
 * @author ichaobuster
 */
public class DateTimeTools {

	private DateTimeFormatter dateTimeFormatter;

	public DateTimeTools() {
		dateTimeFormatter = DateTimeFormatter
				.ofPattern("yyyy-MM-dd EEE HH:mm:ss")
				.withZone(LocaleContextHolder.getTimeZone().toZoneId());
	}

	@Tool(name = "getCurrentDateTime", description = "Get current datetime.")
	public String getCurrentDateTime() {
		return LocalDateTime.now().format(dateTimeFormatter);
	}
}
