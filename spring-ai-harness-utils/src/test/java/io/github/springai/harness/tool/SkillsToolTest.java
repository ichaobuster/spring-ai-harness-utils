/*
 * Copyright 2025 - 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.springai.harness.tool;

import io.github.springai.harness.tool.SkillsTool.Skill;
import io.github.springai.harness.tool.SkillsTool.SkillsFunction;
import io.github.springai.harness.tool.SkillsTool.SkillsInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SkillsTool}.
 *
 * @author ichaobuster
 */
@DisplayName("SkillsTool Tests")
class SkillsToolTest {

	@Test
	@DisplayName("test SkillsInput record")
	void testSkillsInput() {
		SkillsInput input = new SkillsInput("test-command");
		assertThat(input.command()).isEqualTo("test-command");
	}

	@Test
	@DisplayName("test Skill record name and XML formatting")
	void testSkillRecord() {
		Map<String, Object> frontMatter = new LinkedHashMap<>();
		frontMatter.put("name", "my-skill");
		frontMatter.put("description", "This is a test skill");

		Skill skill = new Skill("/path/to/base", frontMatter, "My skill content.");

		assertThat(skill.basePath()).isEqualTo("/path/to/base");
		assertThat(skill.frontMatter()).isEqualTo(frontMatter);
		assertThat(skill.content()).isEqualTo("My skill content.");
		assertThat(skill.name()).isEqualTo("my-skill");

		String xml = skill.toXml();
		assertThat(xml).contains("<skill>");
		assertThat(xml).contains("  <name>my-skill</name>");
		assertThat(xml).contains("  <description>This is a test skill</description>");
		assertThat(xml).contains("</skill>");
	}

	@Test
	@DisplayName("test SkillsFunction apply with existing skill")
	void testSkillsFunctionApplyFound() {
		Map<String, Object> frontMatter = Map.of("name", "pdf");
		Skill skill = new Skill("/path/to/pdf", frontMatter, "PDF skill details");

		Map<String, Skill> skillsMap = Map.of("pdf", skill);
		SkillsFunction function = new SkillsFunction(skillsMap);

		SkillsInput input = new SkillsInput("pdf");
		String result = function.apply(input);

		assertThat(result).isEqualTo("Base directory for this skill: /path/to/pdf\n\nPDF skill details");
	}

	@Test
	@DisplayName("test SkillsFunction apply with non-existing skill")
	void testSkillsFunctionApplyNotFound() {
		Map<String, Skill> skillsMap = Map.of();
		SkillsFunction function = new SkillsFunction(skillsMap);

		SkillsInput input = new SkillsInput("excel");
		String result = function.apply(input);

		assertThat(result).isEqualTo("Skill not found: excel");
	}

	@Test
	@DisplayName("test private toSkillsMap helper method via reflection")
	@SuppressWarnings("unchecked")
	void testToSkillsMap() throws Exception {
		Map<String, Object> frontMatter1 = Map.of("name", "skill1");
		Skill skill1 = new Skill("/path/1", frontMatter1, "content1");

		Map<String, Object> frontMatter2 = Map.of("name", "skill2");
		Skill skill2 = new Skill("/path/2", frontMatter2, "content2");

		List<Skill> skillsList = List.of(skill1, skill2);

		Method method = SkillsTool.class.getDeclaredMethod("toSkillsMap", List.class);
		method.setAccessible(true);

		Map<String, Skill> result = (Map<String, Skill>) method.invoke(null, skillsList);

		assertThat(result).hasSize(2);
		assertThat(result.get("skill1")).isEqualTo(skill1);
		assertThat(result.get("skill2")).isEqualTo(skill2);
	}
}
