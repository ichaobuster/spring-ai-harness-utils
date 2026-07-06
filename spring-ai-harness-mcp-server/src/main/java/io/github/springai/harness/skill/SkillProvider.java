package io.github.springai.harness.skill;

import io.modelcontextprotocol.common.McpTransportContext;

import java.io.IOException;
import java.util.List;

/**
 * SkillProvider interface for discovering and reading skills in workspace.
 *
 * @author buyc
 */
public interface SkillProvider {

	/**
	 * Lists all available skills in the workspace under skills/ directory.
	 *
	 * @param context MCP transport context
	 * @return list of available SkillInfo
	 * @throws IOException if storage error occurs
	 */
	List<SkillInfo> listSkills(McpTransportContext context) throws IOException;

	/**
	 * Reads the full content of SKILL.md for the specified skill name.
	 *
	 * @param context MCP transport context
	 * @param skillName skill name
	 * @return full SKILL.md markdown content formatted with base directory
	 * @throws IOException if storage error occurs
	 */
	String readSkill(McpTransportContext context, String skillName) throws IOException;
}
