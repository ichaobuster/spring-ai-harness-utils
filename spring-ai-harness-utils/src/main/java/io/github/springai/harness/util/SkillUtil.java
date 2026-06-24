package io.github.springai.harness.util;

import io.github.springai.harness.storage.StorageProvider;
import io.github.springai.harness.tool.SkillsTool;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
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

}
