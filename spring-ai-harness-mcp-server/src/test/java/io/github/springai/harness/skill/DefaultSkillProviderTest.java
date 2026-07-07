package io.github.springai.harness.skill;

import io.github.springai.harness.storage.StorageProvider;
import io.github.springai.harness.storage.StorageProviderFactory;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DefaultSkillProvider}.
 */
@DisplayName("DefaultSkillProvider Tests")
@ExtendWith(MockitoExtension.class)
class DefaultSkillProviderTest {

	@Mock
	private StorageProviderFactory storageProviderFactory;

	@Mock
	private StorageProvider userStorageProvider;

	private DefaultSkillProvider skillProvider;
	private McpTransportContext context;

	@BeforeEach
	void setUp() {
		skillProvider = new DefaultSkillProvider(storageProviderFactory);
		context = McpTransportContext.create(Map.of());
	}

	@Test
	@DisplayName("Should list user skills and parse frontmatter")
	void shouldListUserSkills() throws IOException {
		when(storageProviderFactory.getStorageProvider(context)).thenReturn(userStorageProvider);
		when(userStorageProvider.exists("skills")).thenReturn(true);
		when(userStorageProvider.getSeparator()).thenReturn('/');
		when(userStorageProvider.glob("**/SKILL.md", "skills")).thenReturn(List.of("skills/my-skill/SKILL.md"));
		when(userStorageProvider.readString("skills/my-skill/SKILL.md")).thenReturn("""
				---
				name: my-skill
				description: "A cool skill"
				---

				# Instructions
				Follow these steps...
				""");

		List<SkillInfo> skills = skillProvider.listSkills(context);

		assertThat(skills).hasSize(1);
		SkillInfo info = skills.get(0);
		assertThat(info.name()).isEqualTo("my-skill");
		assertThat(info.description()).isEqualTo("A cool skill");
		assertThat(info.basePath()).isEqualTo("skills/my-skill");
		assertThat(info.content()).contains("# Instructions");
	}

	@Test
	@DisplayName("Should read user skill content with base directory header")
	void shouldReadUserSkillContent() throws IOException {
		when(storageProviderFactory.getStorageProvider(context)).thenReturn(userStorageProvider);
		when(userStorageProvider.exists("skills")).thenReturn(true);
		when(userStorageProvider.getSeparator()).thenReturn('/');
		when(userStorageProvider.glob("**/SKILL.md", "skills")).thenReturn(List.of("skills/my-skill/SKILL.md"));
		when(userStorageProvider.readString("skills/my-skill/SKILL.md")).thenReturn("""
				---
				name: my-skill
				description: "A cool skill"
				---

				# Skill Body
				""");

		String content = skillProvider.readSkill(context, "my-skill");

		assertThat(content)
				.startsWith("Base directory for this skill: skills/my-skill")
				.contains("# Skill Body");
	}

	@Test
	@DisplayName("Should throw FileNotFoundException when skill is not found")
	void shouldReturnErrorWhenSkillNotFound() throws IOException {
		when(storageProviderFactory.getStorageProvider(context)).thenReturn(userStorageProvider);
		when(userStorageProvider.exists("skills")).thenReturn(false);

		org.junit.jupiter.api.Assertions.assertThrows(
				java.io.FileNotFoundException.class,
				() -> skillProvider.readSkill(context, "unknown")
		);
	}

	@Test
	@DisplayName("Should throw IllegalArgumentException when skillName is empty")
	void shouldThrowExceptionWhenSkillNameIsEmpty() {
		org.junit.jupiter.api.Assertions.assertThrows(
				IllegalArgumentException.class,
				() -> skillProvider.readSkill(context, "")
		);
	}

	@Test
	@DisplayName("Should return empty list when skills folder does not exist")
	void shouldReturnEmptyListWhenSkillsFolderDoesNotExist() throws IOException {
		when(storageProviderFactory.getStorageProvider(context)).thenReturn(userStorageProvider);
		when(userStorageProvider.exists("skills")).thenReturn(false);

		List<SkillInfo> result = skillProvider.listSkills(context);
		assertThat(result).isEmpty();
	}
}
