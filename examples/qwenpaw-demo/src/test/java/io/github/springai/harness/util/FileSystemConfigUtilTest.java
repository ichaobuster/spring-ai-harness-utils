package io.github.springai.harness.util;

import io.github.springai.harness.config.AgentConfig;
import io.github.springai.harness.storage.LocalFileStorage;
import io.github.springai.harness.storage.StorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FileSystemConfigUtilTest {

	AgentConfig defaultConfig;

	@TempDir
	Path tempDir;

	Path baseDir;

	StorageProvider storageProvider;

	@BeforeEach
	void setup() throws IOException {
		defaultConfig = new AgentConfig();
		defaultConfig.setAgentId("test_agent2");
		defaultConfig.setModel("test_model2");
		defaultConfig.setNeedPermissionTools(Set.of("Write", "Read"));

		baseDir = tempDir.resolve("config_test");

		storageProvider = new LocalFileStorage(baseDir);
	}

	@Test
	void loadFromFile() throws IOException {
		Files.writeString(baseDir.resolve("config_to_load.json"), "{\"agentId\":\"test_agent\",\"model\":\"test_model\",\"needPermissionTools\":[\"Bash\",\"Write\"]}");
		var result = FileSystemConfigUtil.loadFromFile(storageProvider, "config_to_load.json", AgentConfig.class, defaultConfig);
		assertThat(result).isNotSameAs(defaultConfig);
		assertThat(result.getAgentId()).isEqualTo("test_agent");
		assertThat(result.getModel()).isEqualTo("test_model");
		assertThat(result.getNeedPermissionTools()).contains("Bash", "Write");
	}

	@Test
	void loadFromFile_fileNotExists() {
		var result = FileSystemConfigUtil.loadFromFile(storageProvider, "config_not_exist.json", AgentConfig.class, defaultConfig);
		assertThat(result).isSameAs(defaultConfig);
	}

	@Test
	void loadFromFile_fileNotJson() throws IOException {
		Files.writeString(baseDir.resolve("config_not_json.json"), "foo:bar");
		var result = FileSystemConfigUtil.loadFromFile(storageProvider, "config_not_json.json", AgentConfig.class, defaultConfig);
		assertThat(result).isSameAs(defaultConfig);
	}

	@Test
	void writeConfigIntoFile() throws IOException {
		FileSystemConfigUtil.writeConfigIntoFile(storageProvider, "config_to_write.json", defaultConfig);
		String result = Files.readString(baseDir.resolve("config_to_write.json"));
		assertThat(result).contains("\"agentId\"", "\"test_agent2\"");
		assertThat(result).contains("\"model\"", "\"test_model2\"");
		assertThat(result).contains("\"needPermissionTools\"", "\"Write\"", "\"Read\"");
	}

}