package io.github.springai.harness.controller;

import io.github.springai.harness.task.AgentTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentTaskControllerTest {

	@Mock
	private AgentTaskService agentTaskService;

	AgentTaskController controller;

	@BeforeEach
	void setup() {
		controller = new AgentTaskController(agentTaskService);
	}

	@Test
	void createOneTimeTask() {
		AgentTaskController.OneTimeTaskRequest request = new AgentTaskController.OneTimeTaskRequest("test-user-id", "taskTitle", "testPrompt", 10L);
		when(agentTaskService.createOneTimeTask(eq("test-user-id"), eq("taskTitle"), eq("testPrompt"), eq(10L))).thenReturn("testTaskId");

		String result = controller.createOneTimeTask(request);
		assertThat(result).isEqualTo("testTaskId");
	}

	@Test
	void createScheduledTask() {
		AgentTaskController.ScheduledTaskRequest request = new AgentTaskController.ScheduledTaskRequest("test-user-id", "taskTitle", "testPrompt", "0 8 * * *");
		when(agentTaskService.createCronTask(eq("test-user-id"), eq("taskTitle"), eq("testPrompt"), eq("0 8 * * *"))).thenReturn("testTaskId");
		String result = controller.createScheduledTask(request);
		assertThat(result).isEqualTo("testTaskId");
	}

	@Test
	void removeTask() {
		AgentTaskController.TaskRequest request = new AgentTaskController.TaskRequest("test-user-id", "testTaskId");
		doNothing().when(agentTaskService).removeTask(eq("test-user-id"), eq("testTaskId"));
		controller.removeTask(request);
	}
}