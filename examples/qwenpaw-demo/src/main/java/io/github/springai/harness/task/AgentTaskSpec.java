package io.github.springai.harness.task;

import java.util.Date;

/**
 * ScheduledTaskSpec
 *
 * @author ichaobuster
 */
public class AgentTaskSpec {

	public record CronTask(String taskId, String taskConvId, String taskTitle, String taskPrompt, String cron,
						   Date created) {

		public CronTask copyWithTaskId(String taskId) {
			return new CronTask(taskId, taskConvId, taskTitle, taskPrompt, cron, created);
		}

	}

	public record OneTimeTask(String taskId, String taskConvId, String taskTitle, String taskPrompt,
							  long delayInMinutes, Date created) {
		public OneTimeTask copyWithTaskId(String taskId) {
			return new OneTimeTask(taskId, taskConvId, taskTitle, taskPrompt, delayInMinutes, created);
		}
	}

}
