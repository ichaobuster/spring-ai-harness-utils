package io.github.springai.harness.skill;

import java.util.Map;

/**
 * Represents a SKILL.md file with its relative base directory, parsed frontMatter map, and markdown content.
 *
 * @param basePath relative base directory of the skill (e.g. "skills/my-skill")
 * @param frontMatter key-value metadata map extracted from YAML frontmatter
 * @param content markdown content after frontmatter
 * @author ichaobuster
 */
public record SkillInfo(String basePath, Map<String, Object> frontMatter, String content) {

	/**
	 * Gets the skill name from frontmatter or falls back to directory name.
	 *
	 * @return skill name
	 */
	public String name() {
		if (frontMatter != null && frontMatter.containsKey("name")) {
			Object nameObj = frontMatter.get("name");
			if (nameObj != null && !nameObj.toString().isBlank()) {
				return nameObj.toString().trim();
			}
		}
		if (basePath != null && basePath.contains("/")) {
			return basePath.substring(basePath.lastIndexOf('/') + 1);
		}
		return basePath != null ? basePath : "unknown";
	}

	/**
	 * Gets the skill description from frontmatter or default description.
	 *
	 * @return skill description
	 */
	public String description() {
		if (frontMatter != null && frontMatter.containsKey("description")) {
			Object descObj = frontMatter.get("description");
			if (descObj != null && !descObj.toString().isBlank()) {
				return descObj.toString().trim();
			}
		}
		return "No description provided";
	}
}
