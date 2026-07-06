package io.github.springai.harness.skill;

import io.github.springai.harness.storage.StorageProvider;
import io.github.springai.harness.storage.StorageProviderFactory;
import io.modelcontextprotocol.common.McpTransportContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Default implementation of SkillProvider.
 * Scans user workspace under skills/ directory for SKILL.md files.
 *
 * @author buyc
 */
@Slf4j
public class DefaultSkillProvider implements SkillProvider {

	private final StorageProviderFactory storageProviderFactory;

	public DefaultSkillProvider(StorageProviderFactory storageProviderFactory) {
		this.storageProviderFactory = storageProviderFactory;
	}

	@Override
	public List<SkillInfo> listSkills(McpTransportContext context) throws IOException {
		List<SkillInfo> skills = new ArrayList<>();
		try {
			StorageProvider storageProvider = storageProviderFactory.getStorageProvider(context);
			if (!storageProvider.exists("skills")) {
				return skills;
			}

			List<String> skillFilenames = storageProvider.glob("**/SKILL.md", "skills");
			for (String skillFilename : skillFilenames) {
				String markdown = storageProvider.readString(skillFilename);
				MarkdownParser parser = new MarkdownParser(markdown);
				String sep = String.valueOf(storageProvider.getSeparator());
				String basePath = skillFilename.endsWith(sep + "SKILL.md")
						? skillFilename.substring(0, skillFilename.lastIndexOf(sep + "SKILL.md"))
						: (skillFilename.endsWith("/SKILL.md")
						? skillFilename.substring(0, skillFilename.lastIndexOf("/SKILL.md"))
						: skillFilename);
				skills.add(new SkillInfo(basePath, parser.getFrontMatter(), parser.getContent()));
			}
		} catch (Exception e) {
			log.warn("Failed to list workspace skills: {}", e.getMessage());
		}
		return skills;
	}

	@Override
	public String readSkill(McpTransportContext context, String skillName) throws IOException {
		if (!StringUtils.hasText(skillName)) {
			return "Error: skillName must not be empty.";
		}

		List<SkillInfo> skills = listSkills(context);
		for (SkillInfo skill : skills) {
			if (skillName.trim().equalsIgnoreCase(skill.name())) {
				return String.format("Base directory for this skill: %s\n\n%s", skill.basePath(), skill.content());
			}
		}

		return "Error: Skill not found: " + skillName;
	}
}
