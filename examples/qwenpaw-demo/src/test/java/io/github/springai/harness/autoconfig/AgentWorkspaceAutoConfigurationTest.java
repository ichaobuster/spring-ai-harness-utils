package io.github.springai.harness.autoconfig;

import io.github.springai.harness.HarnessAgentsProperties;
import io.github.springai.harness.storage.StorageProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentWorkspaceAutoConfigurationTest {

	@Test
	void agentWorkspace() {
		var result = new AgentWorkspaceAutoConfiguration()
				.agentWorkspace(mock(StorageProvider.class));
		assertThat(result).isNotNull();
	}

	@Test
	void workspaceProvider_local() {
		HarnessAgentsProperties properties = new HarnessAgentsProperties();
		properties.setStorageProvider("local");
		var result = new AgentWorkspaceAutoConfiguration()
				.workspaceProvider(mock(HarnessAgentsProperties.class));
		assertThat(result).isNotNull();
	}

	@Test
	void workspaceProvider_oss() {
		HarnessAgentsProperties properties = new HarnessAgentsProperties();
		properties.setOssBucket("test-bucket");
		var result = new AgentWorkspaceAutoConfiguration()
				.workspaceProvider(mock(HarnessAgentsProperties.class));
		assertThat(result).isNotNull();
	}

}