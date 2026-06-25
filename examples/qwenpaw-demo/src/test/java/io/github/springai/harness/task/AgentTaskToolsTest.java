package io.github.springai.harness.task;

import io.github.springai.harness.config.AgentConfig;
import io.github.springai.harness.workspace.AgentWorkspace;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentTaskToolsTest {

	@Mock
	AgentTaskService agentTaskService;

	@Mock
	AgentWorkspace agentWorkspace;

	AgentTaskTools tools;

	@BeforeEach
	void setUp() {
		tools = new AgentTaskTools("testAgent", agentTaskService, agentWorkspace);
	}

	@Test
	void createCronTask() {
		when(agentTaskService.createCronTask(eq("testAgent"), eq("testTitle"), eq("testPrompt"), eq("0 0 * * *"))).thenReturn("testTaskId");

		String result = tools.createCronTask("testTitle", "testPrompt", "0 0 * * *");

		assertThat(result).isEqualTo("Task created, task ID: testTaskId");
	}

	@Test
	void createOneTimeTask() {
		when(agentTaskService.createOneTimeTask(eq("testAgent"), eq("testTitle"), eq("testPrompt"), eq(10L))).thenReturn("testTaskId");

		String result = tools.createOneTimeTask("testTitle", "testPrompt", 10L);

		assertThat(result).isEqualTo("Task created, task ID: testTaskId");
	}

	@Test
	void listTasks_noTasks() throws JsonProcessingException {
		AgentConfig agentConfig = new AgentConfig();
		agentConfig.setAgentId("testAgent");
		agentConfig.setCronTasks(List.of());
		agentConfig.setOneTimeTasks(List.of());

		when(agentWorkspace.loadAgentConfig("testAgent")).thenReturn(agentConfig);

		String result = tools.listTasks("testAgent");

		assertThat(result).isEqualTo("No tasks.");
	}

	@Test
	void listTasks_noOneTimeTasks() throws JsonProcessingException {
		AgentConfig agentConfig = new AgentConfig();
		agentConfig.setAgentId("testAgent");
		agentConfig.setCronTasks(List.of(new AgentTaskSpec.CronTask("testCronTask", "testCronConvId", "testCronTitle", "testCronPrompt", "0 0 * * *", new Date())));
		agentConfig.setOneTimeTasks(List.of());

		when(agentWorkspace.loadAgentConfig("testAgent")).thenReturn(agentConfig);

		String result = tools.listTasks("testAgent");

		assertThat(result).contains("Cron tasks:").contains("testCronTask")
				.contains("No one time tasks.");
	}

	@Test
	void listTasks_noCronTasks() throws JsonProcessingException {
		AgentConfig agentConfig = new AgentConfig();
		agentConfig.setAgentId("testAgent");
		agentConfig.setCronTasks(List.of());
		agentConfig.setOneTimeTasks(List.of(new AgentTaskSpec.OneTimeTask("testOneTimeTask", "testOneTimeConvId", "testOneTimeTitle", "testOneTimePrompt", 10L, new Date())));

		when(agentWorkspace.loadAgentConfig("testAgent")).thenReturn(agentConfig);

		String result = tools.listTasks("testAgent");

		assertThat(result).contains("One time tasks:").contains("testOneTimeTask")
				.contains("No cron tasks.");
	}

	@Test
	void listTasks() throws JsonProcessingException {
		AgentConfig agentConfig = new AgentConfig();
		agentConfig.setAgentId("testAgent");
		agentConfig.setCronTasks(List.of(new AgentTaskSpec.CronTask("testCronTask", "testCronConvId", "testCronTitle", "testCronPrompt", "0 0 * * *", new Date())));
		agentConfig.setOneTimeTasks(List.of(new AgentTaskSpec.OneTimeTask("testOneTimeTask", "testOneTimeConvId", "testOneTimeTitle", "testOneTimePrompt", 10L, new Date())));

		when(agentWorkspace.loadAgentConfig("testAgent")).thenReturn(agentConfig);

		String result = tools.listTasks("testAgent");

		assertThat(result).contains("Cron tasks:").contains("testCronTask")
				.contains("One time tasks:").contains("testOneTimeTask");
	}

	@Test
	void removeTask() {
		doNothing().when(agentTaskService).removeTask(eq("testAgent"), eq("testTaskId"));

		String result = tools.removeTask("testTaskId");

		assertThat(result).isEqualTo("Task removed.");
	}
}