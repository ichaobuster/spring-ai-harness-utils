package io.github.springai.harness.skill;

import java.util.HashMap;
import java.util.Map;

/**
 * Parser for Markdown documents with optional YAML front matter.
 *
 * @author ichaobuster
 */
public class MarkdownParser {

	private final Map<String, Object> frontMatter = new HashMap<>();
	private String content = "";

	public MarkdownParser(String markdown) {
		if (markdown == null || markdown.isEmpty()) {
			return;
		}

		if (markdown.startsWith("---")) {
			int endIndex = markdown.indexOf("---", 3);
			if (endIndex != -1) {
				String frontMatterSection = markdown.substring(3, endIndex).trim();
				parseFrontMatter(frontMatterSection);
				content = markdown.substring(endIndex + 3).trim();
			} else {
				content = markdown;
			}
		} else {
			content = markdown;
		}
	}

	private void parseFrontMatter(String frontMatterSection) {
		String[] lines = frontMatterSection.split("\n");
		for (String line : lines) {
			line = line.trim();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			int colonIndex = line.indexOf(':');
			if (colonIndex > 0) {
				String key = line.substring(0, colonIndex).trim();
				String value = line.substring(colonIndex + 1).trim();
				value = removeQuotes(value);
				frontMatter.put(key, value);
			}
		}
	}

	private String removeQuotes(String value) {
		if (value.length() >= 2) {
			if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
				return value.substring(1, value.length() - 1);
			}
		}
		return value;
	}

	public Map<String, Object> getFrontMatter() {
		return new HashMap<>(frontMatter);
	}

	public String getContent() {
		return content;
	}
}
