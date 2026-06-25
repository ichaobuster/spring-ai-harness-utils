package io.github.springai.harness.task;

import io.github.springai.harness.config.AgentConfig;
import io.github.springai.harness.workspace.AgentWorkspace;
import com.cronutils.mapper.CronMapper;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.CronSchedule;
import org.redisson.api.RScheduledExecutorService;
import org.redisson.api.RScheduledFuture;
import org.redisson.api.RedissonClient;
import org.redisson.executor.CronExpression;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * AgentTaskService
 *
 * @author ichaobuster
 */
@Slf4j
@Service
public class AgentTaskService {

	private static final String TASK_CONV_ID_PREFIX = "task-";

	private static final Integer MAX_SCHEDULED_TASKS_COUNT = 1;

	private static final Integer MAX_ONE_TIME_TASKS_COUNT = 2;

	/**
	 * 一次性任务派发后15分钟没有worker处理的话，直接过期
	 */
	private static final Long ONE_TIME_TASK_TTL_IN_MINUTES = 15L;

	private static final Integer CHECK_CRON_EXPRESSION_TIMES = 100;

	private final RedissonClient redissonClient;

	private final AgentWorkspace agentWorkspace;

	private final RScheduledExecutorService executorService;

	private final CronParser unixCronParser;

	public AgentTaskService(@Autowired RedissonClient redissonClient, @Autowired AgentWorkspace agentWorkspace) {
		this.redissonClient = redissonClient;
		this.agentWorkspace = agentWorkspace;

		this.executorService = redissonClient.getExecutorService(AgentTaskConst.AGENT_TASK_EXECUTOR_NAME);
		this.unixCronParser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));
	}

	/**
	 * 创建定时任务
	 *
	 * @param unixCron 必须是5位的 unit cron 表达式，并且必须保证间隔大于1小时
	 * @return 任务ID
	 */
	public String createCronTask(String agentId, String taskTitle, String taskPrompt, String unixCron) {
		String quartzCron = CronMapper.fromUnixToQuartz().map(unixCronParser.parse(unixCron)).asString();
		checkIsIntervalAtLeastOneHour(quartzCron);

		AgentConfig agentConfig = agentWorkspace.loadAgentConfig(agentId);
		if (agentConfig.getCronTasks().size() >= MAX_SCHEDULED_TASKS_COUNT) {
			throw new RuntimeException("已达到定时任务数量上限: " + MAX_SCHEDULED_TASKS_COUNT);
		}

		String taskConvId = TASK_CONV_ID_PREFIX + UUID.randomUUID().toString();
		AgentTaskSpec.CronTask task = new AgentTaskSpec.CronTask(null, taskConvId, taskTitle, taskPrompt, unixCron, Date.from(Instant.now()));

		RScheduledFuture<?> future = executorService.schedule(new AgentCronTask(agentId, task), CronSchedule.of(quartzCron));
		String taskId = future.getTaskId();
		log.info("Scheduled task created: " + taskId);

		AgentTaskSpec.CronTask task2Store = task.copyWithTaskId(taskId);
		agentConfig.getCronTasks().add(task2Store);
		agentWorkspace.writeAgentConfig(agentConfig);

		return taskId;
	}

	/**
	 * 通过100次推演校验cron表达式是否至少间隔1小时
	 */
	private void checkIsIntervalAtLeastOneHour(String quartzCron) {
		// 1. 尝试解析 Cron 表达式，如果语法错误会抛出 IllegalArgumentException
		CronExpression cronExpression = new CronExpression(quartzCron);
		// 2. 设定一个基准时间开始推演（采用 UTC+8 时区确保跨天/跨月的计算精确度）
		Date currentTime = new Date();
		// 3. 模拟推演未来 100 次执行时间
		for (int i = 0; i < CHECK_CRON_EXPRESSION_TIMES; i++) {
			Date nextTime = cronExpression.getNextValidTimeAfter(currentTime);
			// 如果返回 null，说明该任务不会再执行了（例如限定了只在过去的某一年执行）
			if (nextTime == null) {
				break;
			}
			// 从第二次开始计算间隔
			if (i > 0) {
				// 计算相邻两次执行时间的差值（单位：分钟）
				long diffMinutes = (nextTime.getTime() - currentTime.getTime()) / (1000 * 60);
				// 只要发现任意一次的间隔小于 60 分钟，直接判定为不合规
				if (diffMinutes < 60) {
					throw new IllegalArgumentException("Cron expression must have at least an interval of 1 hour");
				}
			}
			// 将当前时间推进到下一次执行时间，继续下一次循环
			currentTime = nextTime;
		}
		// 历经 100 次推演都没发现小于 1 小时的间隔，判定为合规
	}

	/**
	 * 创建一次性任务
	 *
	 * @return 任务ID
	 */
	public String createOneTimeTask(String agentId, String taskTitle, String taskPrompt, long delayInMinutes) {
		AgentConfig agentConfig = agentWorkspace.loadAgentConfig(agentId);
		// 移除已执行过或已过期任务
		List<AgentTaskSpec.OneTimeTask> tasksToRemove = agentConfig.getOneTimeTasks().stream()
				.filter(task -> task.created().toInstant().plus(task.delayInMinutes(), ChronoUnit.MINUTES).isBefore(Instant.now()))
				.collect(Collectors.toList());
		tasksToRemove.forEach(task -> executorService.cancelTask(task.taskId()));
		agentConfig.getOneTimeTasks().removeIf(task -> tasksToRemove.contains(task));
		if (agentConfig.getOneTimeTasks().size() >= MAX_ONE_TIME_TASKS_COUNT) {
			throw new RuntimeException("已达到一次性任务数量上限: " + MAX_ONE_TIME_TASKS_COUNT);
		}

		String taskConvId = TASK_CONV_ID_PREFIX + UUID.randomUUID().toString();
		AgentTaskSpec.OneTimeTask task = new AgentTaskSpec.OneTimeTask(null, taskConvId, taskTitle, taskPrompt, delayInMinutes, Date.from(Instant.now()));

		RScheduledFuture<?> future = executorService.schedule(
				new AgentOneTimeTask(agentId, task),
//				delayInMinutes, TimeUnit.MINUTES, delayInMinutes + ONE_TIME_TASK_TTL_IN_MINUTES, TimeUnit.MINUTES);
				delayInMinutes, TimeUnit.MINUTES);
		String taskId = future.getTaskId();
		log.info("OneTime task created: " + taskId);

		AgentTaskSpec.OneTimeTask task2Store = task.copyWithTaskId(taskId);
		agentConfig.getOneTimeTasks().add(task2Store);
		agentWorkspace.writeAgentConfig(agentConfig);

		return taskId;
	}

	/**
	 * 移除定时任务
	 *
	 * @param taskId
	 */
	public void removeTask(String agentId, String taskId) {
		Boolean result = executorService.cancelTask(taskId);
		log.debug("Cancel task " + taskId + " result: " + result);
		log.info("Task removed: " + taskId);

		AgentConfig agentConfig = agentWorkspace.loadAgentConfig(agentId);
		agentConfig.getCronTasks().removeIf(task -> task.taskId().equals(taskId));
		agentConfig.getOneTimeTasks().removeIf(task -> task.taskId().equals(taskId));
		agentWorkspace.writeAgentConfig(agentConfig);
		log.info("Agent config updated: " + agentConfig);
	}

}
