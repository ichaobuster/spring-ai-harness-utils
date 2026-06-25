package io.github.springai.harness.autoconfig;

import io.github.springai.harness.HarnessAgentsProperties;
import io.github.springai.harness.workspace.AgentWorkspace;
import io.github.springai.harness.storage.LocalFileStorage;
import io.github.springai.harness.storage.StorageProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * HarnessAgentsAutoConfiguration
 *
 * @author ichaobuster
 */
@Configuration
@ConditionalOnClass({AgentWorkspace.class})
@EnableConfigurationProperties({HarnessAgentsProperties.class})
public class AgentWorkspaceAutoConfiguration {

	@Bean
	public AgentWorkspace agentWorkspace(@Qualifier("workspaceProvider") StorageProvider storageProvider) {
		return new AgentWorkspace(storageProvider);
	}

	@Bean(name = "workspaceProvider")
	public StorageProvider workspaceProvider(HarnessAgentsProperties properties) {
		// TODO replace with OSS Provider
		return LocalFileStorage.builder().baseDir(properties.getWorkspaceDir()).build();
	}

}
