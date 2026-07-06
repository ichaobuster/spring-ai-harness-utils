package io.github.springai.harness.skill;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.ObjectListing;
import io.github.springai.harness.autoconfig.HarnessMcpServerProperties;
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
import static org.mockito.ArgumentMatchers.any;
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

	@Mock
	private OSS ossClient;

	private HarnessMcpServerProperties properties;
	private DefaultSkillProvider skillProvider;
	private McpTransportContext context;

	@BeforeEach
	void setUp() {
		properties = new HarnessMcpServerProperties();
		properties.setOssBucket("test-bucket");
		properties.setGlobalSkillsPrefix("mcp/global/skills/");

		skillProvider = new DefaultSkillProvider(storageProviderFactory, properties, ossClient);
		context = McpTransportContext.create(Map.of());
	}

	@Test
	@DisplayName("Should list user skills and parse frontmatter")
	void shouldListUserSkills() throws IOException {
		when(storageProviderFactory.getStorageProvider(context)).thenReturn(userStorageProvider);
		when(ossClient.listObjects(any(ListObjectsRequest.class))).thenReturn(new ObjectListing());
		when(userStorageProvider.exists("skills")).thenReturn(true);
		when(userStorageProvider.isDirectory("skills")).thenReturn(true);

		when(userStorageProvider.listDirectory("skills")).thenReturn(List.of(
				new StorageProvider.Info("my-skill", true, true, 0, 0)
		));
		when(userStorageProvider.exists("skills/my-skill/SKILL.md")).thenReturn(true);
		when(userStorageProvider.readString("skills/my-skill/SKILL.md")).thenReturn("""
				---
				name: my-skill
				description: "A cool skill"
				---

				# Instructions
				""");

		List<SkillInfo> skills = skillProvider.listSkills(context);

		assertThat(skills).hasSize(1);
		SkillInfo info = skills.get(0);
		assertThat(info.name()).isEqualTo("my-skill");
		assertThat(info.description()).isEqualTo("A cool skill");
		assertThat(info.source()).isEqualTo("user");
	}

	@Test
	@DisplayName("Should read user skill content")
	void shouldReadUserSkillContent() throws IOException {
		when(storageProviderFactory.getStorageProvider(context)).thenReturn(userStorageProvider);
		when(userStorageProvider.exists("skills/my-skill/SKILL.md")).thenReturn(true);
		when(userStorageProvider.readString("skills/my-skill/SKILL.md")).thenReturn("Skill Content");

		String content = skillProvider.readSkill(context, "my-skill");

		assertThat(content).isEqualTo("Skill Content");
	}

	@Test
	@DisplayName("Should return error message when skill is not found")
	void shouldReturnErrorWhenSkillNotFound() throws IOException {
		when(storageProviderFactory.getStorageProvider(context)).thenReturn(userStorageProvider);
		when(userStorageProvider.exists("skills/unknown/SKILL.md")).thenReturn(false);
		when(userStorageProvider.listDirectory("skills")).thenReturn(List.of());
		when(ossClient.listObjects(any(ListObjectsRequest.class))).thenReturn(new ObjectListing());

		String result = skillProvider.readSkill(context, "unknown");

		assertThat(result).isEqualTo("Error: Skill not found: unknown");
	}
}
