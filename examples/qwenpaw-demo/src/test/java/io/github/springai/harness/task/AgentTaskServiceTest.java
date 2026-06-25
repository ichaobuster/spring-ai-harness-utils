package io.github.springai.harness.task;

import io.github.springai.harness.config.AgentConfig;
import io.github.springai.harness.workspace.AgentWorkspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.CronSchedule;
import org.redisson.api.RScheduledExecutorService;
import org.redisson.api.RScheduledFuture;
import org.redisson.api.RedissonClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentTaskServiceTest {

	@Mock
	private RedissonClient redissonClient;

	@Mock
	private AgentWorkspace agentWorkspace;

	@Mock
	private RScheduledExecutorService executorService;

	AgentTaskService agentTaskService;

	@BeforeEach
	void setup() {
		when(redissonClient.getExecutorService(eq(AgentTaskConst.AGENT_TASK_EXECUTOR_NAME))).thenReturn(executorService);
		agentTaskService = new AgentTaskService(redissonClient, agentWorkspace);
	}

	@Test
	void testCreateScheduledTask() {
		RScheduledFuture future = mock(RScheduledFuture.class);
		AgentConfig agentConfig = new AgentConfig("test-agent");
		when(agentWorkspace.loadAgentConfig(eq("test-agent"))).thenReturn(agentConfig);
		when(executorService.schedule(any(AgentCronTask.class), any(CronSchedule.class))).thenReturn(future);
		when(future.getTaskId()).thenReturn("testTaskId");
		doNothing().when(agentWorkspace).writeAgentConfig(eq(agentConfig));

		String taskId = agentTaskService.createCronTask("test-agent", "taskTitle", "testPrompt", "0 8 * * *");
		assertThat(taskId).isEqualTo("testTaskId");
		assertThat(agentConfig.getCronTasks()).hasSize(1);
		assertThat(agentConfig.getCronTasks().get(0).taskId()).isEqualTo("testTaskId");
		assertThat(agentConfig.getCronTasks().get(0).taskPrompt()).isEqualTo("testPrompt");
	}

	@Test
	void testCreateScheduledTask_countExceeded() {
		AgentConfig agentConfig = new AgentConfig("test-agent");
		agentConfig.getCronTasks().add(new AgentTaskSpec.CronTask("taskId1", "taskConvId1", "taskTitle1", "testPrompt1", "0 8 * * *", Date.from(Instant.now())));
		when(agentWorkspace.loadAgentConfig(eq("test-agent"))).thenReturn(agentConfig);
		assertThatThrownBy(() -> agentTaskService.createCronTask("test-agent", "taskTitle2", "testPrompt2", "0 8 * * *"))
				.isInstanceOf(RuntimeException.class);
	}

	@Test
	void testCreateScheduledTask_invalidCronExpress() {
		assertThatThrownBy(() -> agentTaskService.createCronTask("test-agent", "taskTitle", "testPrompt", "0 * * *"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void testCreateScheduledTask_intervalLessThenOneHour() {
		assertThatThrownBy(() -> agentTaskService.createCronTask("test-agent", "taskTitle", "testPrompt", "0,30 * * * *"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Cron expression must have at least an interval of 1 hour");
	}

	@Test
	void testCreateOneTimeTask() {
		RScheduledFuture future = mock(RScheduledFuture.class);
		AgentConfig agentConfig = new AgentConfig("test-agent");
		when(agentWorkspace.loadAgentConfig(eq("test-agent"))).thenReturn(agentConfig);
		when(executorService.schedule(any(AgentOneTimeTask.class), any(Long.class), eq(TimeUnit.MINUTES))).thenReturn(future);
		when(future.getTaskId()).thenReturn("testTaskId");
		doNothing().when(agentWorkspace).writeAgentConfig(eq(agentConfig));

		String taskId = agentTaskService.createOneTimeTask("test-agent", "taskTitle", "testPrompt", 30);
		assertThat(taskId).isEqualTo("testTaskId");
		assertThat(agentConfig.getOneTimeTasks()).hasSize(1);
		assertThat(agentConfig.getOneTimeTasks().get(0).taskId()).isEqualTo("testTaskId");
		assertThat(agentConfig.getOneTimeTasks().get(0).taskPrompt()).isEqualTo("testPrompt");
	}

	@Test
	void testCreateOneTimeTask_countExceeded() {
		AgentConfig agentConfig = new AgentConfig("test-agent");
		agentConfig.getOneTimeTasks().add(new AgentTaskSpec.OneTimeTask("taskId1", "taskConvId1", "taskTitle1", "testPrompt1", 100, Date.from(Instant.now())));
		agentConfig.getOneTimeTasks().add(new AgentTaskSpec.OneTimeTask("taskId2", "taskConvId2", "taskTitle2", "testPrompt2", 100, Date.from(Instant.now())));
		when(agentWorkspace.loadAgentConfig(eq("test-agent"))).thenReturn(agentConfig);
		assertThatThrownBy(() -> agentTaskService.createOneTimeTask("test-agent", "taskTitle2", "testPrompt2", 10))
				.isInstanceOf(RuntimeException.class);
	}

	@Test
	void testCreateOneTimeTask_removeExpiredTasks() {
		RScheduledFuture future = mock(RScheduledFuture.class);
		AgentConfig agentConfig = new AgentConfig("test-agent");
		agentConfig.getOneTimeTasks().add(new AgentTaskSpec.OneTimeTask("taskId1", "taskConvId1", "taskTitle1", "testPrompt1", 1, Date.from(Instant.now().minus(10, ChronoUnit.MINUTES))));
		agentConfig.getOneTimeTasks().add(new AgentTaskSpec.OneTimeTask("taskId2", "taskConvId2", "taskTitle2", "testPrompt2", 1, Date.from(Instant.now().minus(10, ChronoUnit.MINUTES))));

		when(agentWorkspace.loadAgentConfig(eq("test-agent"))).thenReturn(agentConfig);
		when(executorService.schedule(any(AgentOneTimeTask.class), any(Long.class), eq(TimeUnit.MINUTES))).thenReturn(future);
		when(executorService.cancelTask(anyString())).thenReturn(true);
		when(future.getTaskId()).thenReturn("testTaskId");
		doNothing().when(agentWorkspace).writeAgentConfig(eq(agentConfig));

		String taskId = agentTaskService.createOneTimeTask("test-agent", "taskTitle", "testPrompt", 30);
		assertThat(taskId).isEqualTo("testTaskId");
		assertThat(agentConfig.getOneTimeTasks()).hasSize(1);
		assertThat(agentConfig.getOneTimeTasks().get(0).taskId()).isEqualTo("testTaskId");
		assertThat(agentConfig.getOneTimeTasks().get(0).taskPrompt()).isEqualTo("testPrompt");
	}

	@Test
	void testRemoveTask() {
		AgentConfig agentConfig = new AgentConfig("test-agent");
		agentConfig.getCronTasks().add(new AgentTaskSpec.CronTask("taskId1", "taskConvId1", "taskTitle1", "testPrompt1", "cron1", Date.from(Instant.now())));
		agentConfig.getOneTimeTasks().add(new AgentTaskSpec.OneTimeTask("taskId2", "taskConvId2", "taskTitle2", "testPrompt2", 100, Date.from(Instant.now())));
		when(agentWorkspace.loadAgentConfig(eq("test-agent"))).thenReturn(agentConfig);
		when(executorService.cancelTask(eq("taskId1"))).thenReturn(true);
		doNothing().when(agentWorkspace).writeAgentConfig(eq(agentConfig));

		agentTaskService.removeTask("test-agent", "taskId1");
		assertThat(agentConfig.getCronTasks()).isEmpty();
		assertThat(agentConfig.getOneTimeTasks().size()).isEqualTo(1);
	}

}