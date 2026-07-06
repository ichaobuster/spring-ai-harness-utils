package io.github.springai.harness.skill;

import com.aliyun.oss.OSS;
import io.github.springai.harness.autoconfig.HarnessMcpServerProperties;
import io.github.springai.harness.storage.AliyunOssStorage;
import io.github.springai.harness.storage.StorageProvider;
import io.github.springai.harness.storage.StorageProviderFactory;
import io.modelcontextprotocol.common.McpTransportContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default implementation of SkillProvider.
 * Performs dual-prefix lookup in user workspace (/skills/) and global public skills directory.
 * Standard skill pattern: skills/{skillName}/SKILL.md
 * User skills override global skills with the same name.
 *
 * @author buyc
 */
@Slf4j
public class DefaultSkillProvider implements SkillProvider {

	private static final Pattern NAME_PATTERN = Pattern.compile("(?m)^name:\\s*\"?([^\n\"]+)\"?");
	private static final Pattern DESC_PATTERN = Pattern.compile("(?m)^description:\\s*\"?([^\n\"]+)\"?");

	private final StorageProviderFactory storageProviderFactory;
	private final HarnessMcpServerProperties properties;
	private final OSS ossClient;

	public DefaultSkillProvider(StorageProviderFactory storageProviderFactory, HarnessMcpServerProperties properties, OSS ossClient) {
		this.storageProviderFactory = storageProviderFactory;
		this.properties = properties;
		this.ossClient = ossClient;
	}

	@Override
	public List<SkillInfo> listSkills(McpTransportContext context) throws IOException {
		Map<String, SkillInfo> skillsMap = new LinkedHashMap<>();

		// 1. Global Public Skills
		try {
			StorageProvider globalStorage = getGlobalStorage();
			if (globalStorage.exists("")) {
				scanSkills(globalStorage, "", "global", skillsMap);
			}
		} catch (Exception e) {
			log.warn("Failed to list global skills: {}", e.getMessage());
		}

		// 2. User Workspace Skills (overrides global skills)
		try {
			StorageProvider userStorage = storageProviderFactory.getStorageProvider(context);
			if (userStorage.exists("skills") && userStorage.isDirectory("skills")) {
				scanSkills(userStorage, "skills", "user", skillsMap);
			}
		} catch (Exception e) {
			log.warn("Failed to list user workspace skills: {}", e.getMessage());
		}

		return new ArrayList<>(skillsMap.values());
	}

	@Override
	public String readSkill(McpTransportContext context, String skillName) throws IOException {
		if (!StringUtils.hasText(skillName)) {
			return "Error: skillName must not be empty.";
		}

		// 1. Check user workspace skills first
		try {
			StorageProvider userStorage = storageProviderFactory.getStorageProvider(context);
			String userContent = findAndReadSkillContent(userStorage, "skills", skillName);
			if (userContent != null) {
				return userContent;
			}
		} catch (Exception e) {
			log.warn("Error checking user workspace skill {}: {}", skillName, e.getMessage());
		}

		// 2. Fallback to global public skills
		try {
			StorageProvider globalStorage = getGlobalStorage();
			String globalContent = findAndReadSkillContent(globalStorage, "", skillName);
			if (globalContent != null) {
				return globalContent;
			}
		} catch (Exception e) {
			log.warn("Error checking global skill {}: {}", skillName, e.getMessage());
		}

		return "Error: Skill not found: " + skillName;
	}

	private StorageProvider getGlobalStorage() {
		return new AliyunOssStorage(ossClient, properties.getOssBucket(), properties.getGlobalSkillsPrefix());
	}

	private void scanSkills(StorageProvider storage, String basePath, String source, Map<String, SkillInfo> skillsMap) throws IOException {
		List<StorageProvider.Info> items = storage.listDirectory(basePath);
		for (StorageProvider.Info item : items) {
			if (item.isDirectory()) {
				String dirName = item.path().endsWith("/") ? item.path().substring(0, item.path().length() - 1) : item.path();
				String relPath = StringUtils.hasText(basePath) ? basePath + "/" + dirName : dirName;
				String skillFilePath = relPath + "/SKILL.md";
				if (storage.exists(skillFilePath)) {
					addSkillInfo(storage, skillFilePath, dirName, source, skillsMap);
				}
			}
		}
	}

	private void addSkillInfo(StorageProvider storage, String filePath, String defaultName, String source, Map<String, SkillInfo> skillsMap) {
		try {
			String content = storage.readString(filePath);
			String name = parseFrontmatter(content, NAME_PATTERN, defaultName);
			String desc = parseFrontmatter(content, DESC_PATTERN, "No description provided");
			skillsMap.put(name, new SkillInfo(name, desc, filePath, source));
		} catch (Exception e) {
			log.warn("Failed to read skill at {}: {}", filePath, e.getMessage());
		}
	}

	private String findAndReadSkillContent(StorageProvider storage, String basePath, String skillName) throws IOException {
		String dirPath = StringUtils.hasText(basePath) ? basePath + "/" + skillName + "/SKILL.md" : skillName + "/SKILL.md";
		if (storage.exists(dirPath)) {
			return storage.readString(dirPath);
		}

		// Also check by scanning directory frontmatter name
		List<StorageProvider.Info> items = storage.listDirectory(basePath);
		for (StorageProvider.Info item : items) {
			if (item.isDirectory()) {
				String dirName = item.path().endsWith("/") ? item.path().substring(0, item.path().length() - 1) : item.path();
				String relPath = StringUtils.hasText(basePath) ? basePath + "/" + dirName : dirName;
				String candidatePath = relPath + "/SKILL.md";
				if (storage.exists(candidatePath)) {
					String content = storage.readString(candidatePath);
					String parsedName = parseFrontmatter(content, NAME_PATTERN, null);
					if (skillName.equalsIgnoreCase(parsedName)) {
						return content;
					}
				}
			}
		}

		return null;
	}

	private String parseFrontmatter(String content, Pattern pattern, String defaultValue) {
		if (content == null) {
			return defaultValue;
		}
		Matcher matcher = pattern.matcher(content);
		if (matcher.find()) {
			return matcher.group(1).trim();
		}
		return defaultValue;
	}
}
