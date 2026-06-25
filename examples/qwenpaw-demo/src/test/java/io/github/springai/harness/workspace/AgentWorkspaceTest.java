package io.github.springai.harness.workspace;

import io.github.springai.harness.config.AgentConfig;
import io.github.springai.harness.storage.LocalFileStorage;
import io.github.springai.harness.storage.StorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AgentWorkspaceTest {

	AgentConfig agentConfig;

	AgentWorkspace agentWorkspace;

	@TempDir
	Path tempDir;

	@BeforeEach
	void setup() {
		StorageProvider storageProvider = LocalFileStorage.builder()
				.baseDir(tempDir.toAbsolutePath())
				.build();

		agentConfig = new AgentConfig();
		agentConfig.setAgentId("test");
		agentConfig.setModel("TestModel");
		agentConfig.setContextWindow(10_000);
		agentConfig.setMaxOutputTokens(2000);

		agentWorkspace = new AgentWorkspace(storageProvider);
	}

	@Test
	void loadAgentConfig() {
		var result = agentWorkspace.loadAgentConfig("test");
		assertThat(result).isNotNull();
	}

	@Test
	void writeAgentConfig() {
		agentWorkspace.writeAgentConfig(agentConfig);
		assertThat(Files.exists(tempDir.resolve("test/" + AgentConfig.FILE_NAME_TEMPLATE))).isTrue();
	}
}