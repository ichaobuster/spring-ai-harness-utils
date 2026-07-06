package io.github.springai.harness.tool;

import io.github.springai.harness.skill.SkillInfo;
import io.github.springai.harness.skill.SkillProvider;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SkillsTools}.
 */
@DisplayName("SkillsTools Unit Tests")
@ExtendWith(MockitoExtension.class)
class SkillsToolsTest {

	@Mock
	private SkillProvider skillProvider;

	@InjectMocks
	private SkillsTools skillsTools;

	private McpTransportContext context;

	@BeforeEach
	void setUp() {
		context = McpTransportContext.create(Map.of());
	}

	@Nested
	@DisplayName("MCP Tools Tests")
	class McpToolsTests {

		@Test
		@DisplayName("Should return no skills message when list is empty")
		void shouldReturnNoSkillsMessage() throws IOException {
			when(skillProvider.listSkills(any())).thenReturn(Collections.emptyList());

			String result = skillsTools.listSkills(context);

			assertThat(result).isEqualTo("No skills found.");
		}

		@Test
		@DisplayName("Should return formatted skills list")
		void shouldReturnFormattedSkillsList() throws IOException {
			when(skillProvider.listSkills(any())).thenReturn(List.of(
					new SkillInfo("skills/code-review", Map.of("name", "code-review", "description", "Reviews code"), "# Content")
			));

			String result = skillsTools.listSkills(context);

			assertThat(result)
					.contains("Available Skills:")
					.contains("code-review")
					.contains("skills/code-review")
					.contains("Reviews code");
		}

		@Test
		@DisplayName("Should return error when skillName is blank")
		void shouldReturnErrorWhenSkillNameIsBlank() {
			String result = skillsTools.readSkill(context, "   ");

			assertThat(result).isEqualTo("Error: skillName must not be empty.");
		}

		@Test
		@DisplayName("Should return skill content when skillName is valid")
		void shouldReturnSkillContent() throws IOException {
			when(skillProvider.readSkill(any(), eq("code-review"))).thenReturn("Base directory for this skill: skills/code-review\n\n# Code Review Instructions");

			String result = skillsTools.readSkill(context, "code-review");

			assertThat(result).contains("# Code Review Instructions");
		}
	}

	@Nested
	@DisplayName("MCP Resources Tests")
	class McpResourcesTests {

		@Test
		@DisplayName("Should list skills via MCP Resource skill://list")
		void shouldListSkillsViaResource() throws IOException {
			when(skillProvider.listSkills(any())).thenReturn(List.of(
					new SkillInfo("skills/pdf", Map.of("name", "pdf", "description", "PDF Helper"), "# PDF")
			));

			String result = skillsTools.listSkillsResource(context);

			assertThat(result)
					.contains("Available Skills:")
					.contains("pdf");
		}

		@Test
		@DisplayName("Should read skill content via MCP Resource skill://{skillName}")
		void shouldReadSkillViaResource() throws IOException {
			when(skillProvider.readSkill(any(), eq("pdf"))).thenReturn("Base directory for this skill: skills/pdf\n\n# PDF Instructions");

			String result = skillsTools.readSkillResource(context, "pdf");

			assertThat(result).contains("# PDF Instructions");
		}
	}
}
