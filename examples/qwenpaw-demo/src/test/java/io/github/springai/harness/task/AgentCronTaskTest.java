package io.github.springai.harness.task;

import io.github.springai.harness.agent.HarnessAgents;
import io.github.springai.harness.config.AgentConfig;
import io.github.springai.harness.workspace.AgentWorkspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentCronTaskTest {

	@Mock
	private AgentWorkspace agentWorkspace;
	@Mock
	private HarnessAgents harnessAgents;

	AgentCronTask agentCronTask;

	@BeforeEach
	void setup() {
		agentCronTask = new AgentCronTask("test-agent", new AgentTaskSpec.CronTask("taskId", "taskConvId", "taskTitle", "testPrompt", "0 0 8 * * ?", Date.from(Instant.now())));
		agentCronTask.setAgentWorkspace(agentWorkspace);
		agentCronTask.setHarnessAgents(harnessAgents);
	}

	@Test
	void testRun() {
		AgentConfig agentConfig = new AgentConfig("test-agent");

		when(agentWorkspace.loadAgentConfig(eq("test-agent"))).thenReturn(agentConfig);
		when(harnessAgents.chat(eq(agentConfig), eq("taskConvId"), eq("testPrompt"), any(List.class), any())).thenReturn(Flux.empty());

		agentCronTask.run();
	}

}