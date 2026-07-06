package io.github.springai.harness.skill;

/**
 * Information record representing a skill.
 *
 * @param name skill name
 * @param description short description of the skill
 * @param path relative file path to SKILL.md
 * @param source skill source ("user" or "global")
 * @author buyc
 */
public record SkillInfo(String name, String description, String path, String source) {
}
