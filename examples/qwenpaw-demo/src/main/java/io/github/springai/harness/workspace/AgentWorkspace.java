package io.github.springai.harness.workspace;

import io.github.springai.harness.config.AgentConfig;
import io.github.springai.harness.util.FileSystemConfigUtil;
import io.github.springai.harness.storage.StorageProvider;
import lombok.extern.slf4j.Slf4j;

/**
 * Workspace
 *
 * @author ichaobuster
 */
@Slf4j
public class AgentWorkspace {

	private final StorageProvider storageProvider;

	public AgentWorkspace(StorageProvider storageProvider) {
		this.storageProvider = storageProvider;
	}

	public StorageProvider initUserWorkspace(AgentConfig config) {
		return initUserWorkspace(config.getAgentId());
	}

	public StorageProvider initUserWorkspace(String agentId) {
		return this.storageProvider.subDirProvider(agentId);
	}

	public AgentConfig loadAgentConfig(String agentId) {
		StorageProvider workspaceDir = initUserWorkspace(agentId);
		return FileSystemConfigUtil.loadFromFile(
				workspaceDir,
				AgentConfig.FILE_NAME_TEMPLATE,
				AgentConfig.class,
				new AgentConfig(agentId)
		);
	}

	public void writeAgentConfig(AgentConfig agentConfig) {
		StorageProvider userWorkspace = initUserWorkspace(agentConfig);
		FileSystemConfigUtil.writeConfigIntoFile(userWorkspace, AgentConfig.FILE_NAME_TEMPLATE, agentConfig);
		log.info("Agent config updated: " + agentConfig);
	}

}
