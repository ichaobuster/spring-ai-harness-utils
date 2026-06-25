package io.github.springai.harness.controller;

import io.github.springai.harness.task.AgentTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AgentTaskController
 *
 * @author ichaobuster
 */
@Slf4j
@RestController
@RequestMapping("/tasks")
public class AgentTaskController {

	private final AgentTaskService agentTaskService;

	public AgentTaskController(@Autowired AgentTaskService agentTaskService) {
		this.agentTaskService = agentTaskService;
	}

	@PostMapping("/onetime/create")
	public String createOneTimeTask(@RequestBody OneTimeTaskRequest request) {
		return agentTaskService.createOneTimeTask(request.userId(), request.taskTitle(), request.taskPrompt(), request.delayInMinutes());
	}

	@PostMapping("/scheduled/create")
	public String createScheduledTask(@RequestBody ScheduledTaskRequest request) {
		return agentTaskService.createCronTask(request.userId(), request.taskTitle(), request.taskPrompt(), request.cron());
	}

	@PostMapping("/remove")
	public void removeTask(@RequestBody TaskRequest request) {
		agentTaskService.removeTask(request.userId(), request.taskId());
	}

	public record TaskRequest(String userId, String taskId) {
	}

	public record OneTimeTaskRequest(String userId, String taskTitle, String taskPrompt,
									 Long delayInMinutes) {
	}

	public record ScheduledTaskRequest(String userId, String taskTitle, String taskPrompt,
									   String cron) {
	}

}
