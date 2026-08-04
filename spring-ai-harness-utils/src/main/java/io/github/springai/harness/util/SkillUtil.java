package io.github.springai.harness.util;

import io.github.springai.harness.storage.StorageProvider;
import io.github.springai.harness.tool.SkillsTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * SkillUtil
 *
 * @author ichaobuster
 */
@Slf4j
public class SkillUtil {

	/**
	 * Loads skills from Aliyun OSS under the given bucket name and prefix.
	 * Recursively finds all SKILL.md files and returns their parsed contents.
	 *
	 * @param storageProvider the StorageProvider instance
	 * @param path            the root path to search for SKILL.md files
	 * @return a list of Skill objects parsed from the discovered SKILL.md files
	 */
	public static List<SkillsTool.Skill> loadStorageProvider(StorageProvider storageProvider, String path) {
		List<SkillsTool.Skill> skills = new ArrayList<>();
		try {
			List<String> skillFilenames = storageProvider.glob("**/SKILL.md", path);
			for (String skillFilename : skillFilenames) {
				String markdown = storageProvider.readString(skillFilename);
				MarkdownParser parser = new MarkdownParser(markdown);
				String basePath = skillFilename.endsWith(storageProvider.getSeparator() + "SKILL.md")
						? skillFilename.substring(0, skillFilename.lastIndexOf(storageProvider.getSeparator()))
						: "";
				skills.add(new SkillsTool.Skill(basePath, parser.getFrontMatter(), parser.getContent()));
			}
		} catch (IOException e) {
			log.error("Failed to find SKILL.md from storageProvider and path " + path, e);
		}

		return skills;
	}

	/**
	 * Loads skills from the classpath matching the default pattern "classpath*:&#42;&#42;/SKILL.md".
	 *
	 * @return a list of Skill objects parsed from the discovered SKILL.md files
	 */
	public static List<SkillsTool.Skill> loadClassPath() {
		return loadClassPath("classpath*:**/SKILL.md");
	}

	/**
	 * Loads skills from the classpath matching the given location pattern.
	 *
	 * @param locationPattern the location pattern to search for SKILL.md files (e.g. "classpath*:skills/&#42;&#42;/SKILL.md")
	 * @return a list of Skill objects parsed from the discovered SKILL.md files
	 */
	public static List<SkillsTool.Skill> loadClassPath(String locationPattern) {
		return loadClassPath(new PathMatchingResourcePatternResolver(), locationPattern);
	}

	/**
	 * Loads skills from the classpath using the provided ResourcePatternResolver and location pattern.
	 *
	 * @param resolver        the ResourcePatternResolver to use
	 * @param locationPattern the location pattern to search for SKILL.md files
	 * @return a list of Skill objects parsed from the discovered SKILL.md files
	 */
	public static List<SkillsTool.Skill> loadClassPath(ResourcePatternResolver resolver, String locationPattern) {
		List<SkillsTool.Skill> skills = new ArrayList<>();
		try {
			Resource[] resources = resolver.getResources(locationPattern);
			for (Resource resource : resources) {
				if (resource.exists() && resource.isReadable()) {
					String markdown = resource.getContentAsString(StandardCharsets.UTF_8);
					MarkdownParser parser = new MarkdownParser(markdown);
					String uriStr = resource.getURI().toString();
					String basePath = "";
					if (uriStr.endsWith("/SKILL.md")) {
						basePath = uriStr.substring(0, uriStr.lastIndexOf("/SKILL.md"));
					}
					skills.add(new SkillsTool.Skill(basePath, parser.getFrontMatter(), parser.getContent()));
				}
			}
		} catch (IOException e) {
			log.error("Failed to load SKILL.md from classpath pattern " + locationPattern, e);
		}
		return skills;
	}

}
