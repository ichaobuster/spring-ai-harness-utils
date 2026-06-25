package io.github.springai.harness.task;

import io.github.springai.harness.agent.HarnessAgents;
import io.github.springai.harness.config.AgentConfig;
import io.github.springai.harness.workspace.AgentWorkspace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.Serializable;
import java.util.List;

/**
 * AgentScheduledTask 基于 Redisson {@link org.redisson.api.RScheduledExecutorService} 实现的定时任务 Worker
 *
 * @author ichaobuster
 */
@Slf4j
public class AgentCronTask implements Runnable, Serializable {

	private String agentId;

	private AgentTaskSpec.CronTask taskParam;

	private transient AgentWorkspace agentWorkspace;

	private transient HarnessAgents harnessAgents;

	public AgentCronTask(String agentId, AgentTaskSpec.CronTask taskParam) {
		this.agentId = agentId;
		this.taskParam = taskParam;
	}

	@Autowired
	public void setAgentWorkspace(AgentWorkspace agentWorkspace) {
		this.agentWorkspace = agentWorkspace;
	}

	@Autowired
	public void setHarnessAgents(HarnessAgents harnessAgents) {
		this.harnessAgents = harnessAgents;
	}

	@Override
	public void run() {
		log.info("Ready to run scheduled task: " + taskParam);
		AgentConfig agentConfig = agentWorkspace.loadAgentConfig(agentId);
		try {
			// 作为 subAgent 处理，不允许定时任务额外再创建新的定时任务
			harnessAgents.chat(agentConfig, this.taskParam.taskConvId(), this.taskParam.taskPrompt(), List.of(), null, true).subscribe();
		} catch (Exception e) {
			log.error("Failed to run task: " + taskParam, e);
		}
	}

}
