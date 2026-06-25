package io.github.springai.harness.controller;

import io.github.springai.harness.agent.HarnessAgents;
import io.github.springai.harness.config.AgentConfig;
import io.github.springai.harness.workspace.AgentWorkspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HarnessAgentsControllerTest {

	HarnessAgentsController controller;

	@Mock
	AgentWorkspace agentWorkspace;
	@Mock
	HarnessAgents harnessAgents;

	@BeforeEach
	void setup() {
		controller = new HarnessAgentsController(agentWorkspace, harnessAgents);
	}

	@Test
	void createSession() {
		var result = controller.createSession();
		assertThat(result).isNotEmpty();
	}

	@Test
	void chat() {
		when(agentWorkspace.loadAgentConfig(eq("testUser"))).thenReturn(new AgentConfig("testUser"));
		when(harnessAgents.chat(any(AgentConfig.class), eq("testConv"), eq("test input"), any(List.class), any()))
				.thenReturn(mock(Flux.class));
		var result = controller.chat(new HarnessAgentsController.ChatRequest("testUser", "testConv", "test input", null));
		assertThat(result).isNotNull();
	}
}