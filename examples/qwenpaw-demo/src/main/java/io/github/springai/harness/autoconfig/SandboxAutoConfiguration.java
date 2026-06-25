package io.github.springai.harness.autoconfig;

import io.github.springai.harness.HarnessAgentsProperties;
import io.github.springai.harness.mcp.AgentMcpClients;
import io.github.springai.harness.sandbox.LazyLoadSandboxCreator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SandboxAutoConfiguration
 *
 * @author ichaobuster
 */
@Configuration
@ConditionalOnClass({LazyLoadSandboxCreator.class})
@EnableConfigurationProperties({HarnessAgentsProperties.class})
@ConditionalOnProperty(prefix = HarnessAgentsProperties.CONFIG_PREFIX, name = "sandbox-mcp.enabled", havingValue = "true", matchIfMissing = false)
public class SandboxAutoConfiguration {

	@Bean
	public LazyLoadSandboxCreator lazyLoadSandboxCreator(AgentMcpClients agentMcpClients, HarnessAgentsProperties properties) {
		return new LazyLoadSandboxCreator(agentMcpClients, properties.getSandboxMcp());
	}

}
