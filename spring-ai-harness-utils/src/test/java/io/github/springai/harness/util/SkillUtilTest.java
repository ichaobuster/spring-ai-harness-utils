package io.github.springai.harness.util;

import io.github.springai.harness.storage.LocalFileStorage;
import io.github.springai.harness.storage.StorageProvider;
import io.github.springai.harness.tool.SkillsTool.Skill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SkillUtil} OSS loading functionality.
 *
 * @author ichaobuster
 */
@DisplayName("Skills OSS Loading Tests")
class SkillUtilTest {

	private static final String SKILL_MD_CONTENT = """
			---
			name: test-skill
			description: A test skill
			---

			This is the test skill content.
			""";

	StorageProvider storageProvider;

	@TempDir
	Path tempDir;

	@BeforeEach
	void setUp() {
		storageProvider = LocalFileStorage.builder()
				.baseDir(tempDir)
				.build();
	}

	@Test
	@DisplayName("should load skills from OSS recursively")
	void loadStorageProvider() throws IOException {
		storageProvider.writeString("test-skills/skill-a/SKILL.md", SKILL_MD_CONTENT);

		List<Skill> skills = SkillUtil.loadStorageProvider(storageProvider, "test-skills");

		assertThat(skills).hasSize(1);
		Skill skill = skills.get(0);
		assertThat(skill.name()).isEqualTo("test-skill");
		assertThat(skill.basePath()).isEqualTo("test-skills" + File.separator + "skill-a");
		assertThat(skill.content()).contains("This is the test skill content.");
	}

	@Test
	@DisplayName("should load skills from classpath")
	void loadClassPath() {
		List<Skill> skills = SkillUtil.loadClassPath();
		assertThat(skills).isNotNull();
	}

	@Test
	@DisplayName("should load skills from classpath with specific pattern")
	void loadClassPathWithPattern() {
		List<Skill> skills = SkillUtil.loadClassPath("classpath*:**/SKILL.md");
		assertThat(skills).isNotNull();
	}
}
