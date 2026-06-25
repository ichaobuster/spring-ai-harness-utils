package io.github.springai.harness.task;

import io.github.springai.harness.config.AgentConfig;
import io.github.springai.harness.workspace.AgentWorkspace;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.util.json.JsonParser;

/**
 * AgentTaskTools
 *
 * @author ichaobuster
 */
public class AgentTaskTools {

	private final String agentId;

	private final AgentTaskService agentTaskService;

	private final AgentWorkspace agentWorkspace;

	public AgentTaskTools(String agentId, AgentTaskService agentTaskService, AgentWorkspace agentWorkspace) {
		this.agentId = agentId;
		this.agentTaskService = agentTaskService;
		this.agentWorkspace = agentWorkspace;
	}

	@Tool(name = "CreateCronTask", description = "Creates a cron task. You must provide a valid 5-field UNIX cron expression, and the execution interval must be at least 1 hour.")
	public String createCronTask(
			@ToolParam(description = "The title of the task.", required = false) String taskTitle,
			@ToolParam(description = "The detailed prompt or specific instructions for the task execution.") String taskPrompt,
			@ToolParam(description = "A valid 5-field UNIX cron expression defining the schedule.") String unixCron) {
		String taskId = agentTaskService.createCronTask(agentId, taskTitle, taskPrompt, unixCron);
		return "Task created, task ID: " + taskId;
	}

	@Tool(name = "CreateOneTimeTask", description = "Creates a one-time delayed task that will be executed after a specified number of minutes.")
	public String createOneTimeTask(
			@ToolParam(description = "The title of the task.", required = false) String taskTitle,
			@ToolParam(description = "The detailed prompt or specific instructions for the task execution.") String taskPrompt,
			@ToolParam(description = "The delay time in minutes before the task is executed.") long delayInMinutes) {
		String taskId = agentTaskService.createOneTimeTask(agentId, taskTitle, taskPrompt, delayInMinutes);
		return "Task created, task ID: " + taskId;
	}

	@Tool(name = "ListScheduledTasks", description = "Lists scheduled tasks.")
	public String listTasks(
			@ToolParam(description = "The unique ID of the task to be removed.") String taskId) throws JsonProcessingException {
		AgentConfig agentConfig = agentWorkspace.loadAgentConfig(agentId);
		if (agentConfig.getCronTasks().isEmpty() && agentConfig.getOneTimeTasks().isEmpty()) {
			return "No tasks.";
		}

		String cronTasksText = agentConfig.getCronTasks().isEmpty() ? "No cron tasks." :
				"Cron tasks:\n```json\n %s\n```".formatted(JsonParser.toJson(agentConfig.getCronTasks()));
		String oneTimeTasksText = agentConfig.getOneTimeTasks().isEmpty() ? "No one time tasks." :
				"One time tasks:\n```json\n %s\n```".formatted(JsonParser.toJson(agentConfig.getOneTimeTasks()));

		return cronTasksText + "\n\n" + oneTimeTasksText;
	}

	@Tool(name = "RemoveScheduledTask", description = "Removes an existing cron or one-time task based on its specific task ID.")
	public String removeTask(
			@ToolParam(description = "The unique ID of the task to be removed.") String taskId) {
		agentTaskService.removeTask(agentId, taskId);
		return "Task removed.";
	}

}
