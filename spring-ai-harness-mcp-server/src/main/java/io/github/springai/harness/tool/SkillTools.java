package io.github.springai.harness.tool;

import io.github.springai.harness.skill.SkillInfo;
import io.github.springai.harness.skill.SkillProvider;
import io.modelcontextprotocol.common.McpTransportContext;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.mcp.annotation.McpArg;
import org.springaicommunity.mcp.annotation.McpResource;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SkillTools exposes skill management capabilities via MCP Tools and MCP Resources.
 *
 * @author buyc
 */
@Component
@Slf4j
public class SkillTools {

	@Autowired
	private SkillProvider skillProvider;

	// @formatter:off
	@McpTool(name = "ListSkills", description = """
		Lists all available skills in the workspace under skills/ directory.
		Returns skill names, base directories, and descriptions.
		Use ReadSkill to view the full instructions for a specific skill.
		""")
	public String listSkills(McpTransportContext context) { // @formatter:on
		try {
			List<SkillInfo> skills = skillProvider.listSkills(context);
			if (skills.isEmpty()) {
				return "No skills found.";
			}

			StringBuilder result = new StringBuilder();
			result.append("Available Skills:\n\n");
			result.append(String.format("%-24s %-30s %s\n", "NAME", "BASE DIRECTORY", "DESCRIPTION"));
			result.append("-".repeat(80)).append("\n");

			for (SkillInfo skill : skills) {
				result.append(String.format("%-24s %-30s %s\n", skill.name(), skill.basePath(), skill.description()));
			}

			return result.toString();
		} catch (Exception e) {
			return "Error listing skills: " + e.getMessage();
		}
	}

	// @formatter:off
	@McpTool(name = "ReadSkill", description = """
		Reads the full content of a specified skill (SKILL.md instructions).

		Usage:
		- Provide the skillName returned by ListSkills.
		- Returns the complete markdown instructions for executing the skill along with its base directory.
		""")
	public String readSkill(
			McpTransportContext context,
			@McpToolParam(description = "The name of the skill to read") String skillName) { // @formatter:on
		try {
			if (skillName == null || skillName.isBlank()) {
				return "Error: skillName must not be empty.";
			}
			return skillProvider.readSkill(context, skillName.trim());
		} catch (Exception e) {
			return "Error reading skill: " + e.getMessage();
		}
	}

	// @formatter:off
	@McpResource(
			name = "ListSkillsResource",
			uri = "skill://list",
			description = "Lists all available workspace skills metadata as an MCP resource",
			mimeType = "text/plain"
	)
	public String listSkillsResource(McpTransportContext context) { // @formatter:on
		return listSkills(context);
	}

	// @formatter:off
	@McpResource(
			name = "ReadSkillResource",
			uri = "skill://{skillName}",
			description = "Reads the instructions of a workspace skill as an MCP resource",
			mimeType = "text/markdown"
	)
	public String readSkillResource(
			McpTransportContext context,
			@McpArg(name = "skillName", description = "The name of the skill to read") String skillName) { // @formatter:on
		return readSkill(context, skillName);
	}
}
