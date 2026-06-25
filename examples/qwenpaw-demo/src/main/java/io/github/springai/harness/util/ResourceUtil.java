package io.github.springai.harness.util;

import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * The tool provides a way to convert static resources into strings. The most common
 * approach is to convert system prompt word resources.
 *
 * @author ichaobuster
 */
public class ResourceUtil {

	public static String loadResourceAsString(Resource resource) {
		Assert.notNull(resource, "resource cannot be null");
		try (InputStream inputStream = resource.getInputStream()) {
			var template = StreamUtils.copyToString(inputStream, Charset.defaultCharset());
//			Assert.hasText(template, "template cannot be null or empty");
			return template;
		} catch (IOException e) {
			throw new RuntimeException("Failed to read resource", e);
		}
	}

}
