package io.github.springai.harness.controller;

import io.github.springai.harness.advisor.HumanInTheLoopAdvisor;
import io.github.springai.harness.agent.HarnessAgents;
import io.github.springai.harness.config.AgentConfig;
import io.github.springai.harness.workspace.AgentWorkspace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

/**
 * HarnessAgentsController
 *
 * @author ichaobuster
 */
@Slf4j
@RestController
@RequestMapping("/agents")
public class HarnessAgentsController {

	private final AgentWorkspace agentWorkspace;

	private final HarnessAgents harnessAgents;

	public HarnessAgentsController(@Autowired AgentWorkspace agentWorkspace, @Autowired HarnessAgents harnessAgents) {
		this.agentWorkspace = agentWorkspace;
		this.harnessAgents = harnessAgents;
	}

	/**
	 * 创建新会话
	 *
	 * @return 会话ID
	 */
	@RequestMapping(path = "/sessions/create", method = {RequestMethod.GET, RequestMethod.POST})
	public String createSession() {
		return UUID.randomUUID().toString();
	}

	@PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ChatClientResponse> chat(@RequestBody ChatRequest request) {
		// TODO userId 的其他获取方法
		AgentConfig agentConfig = agentWorkspace.loadAgentConfig(request.userId);
		return harnessAgents.chat(agentConfig, request.conversationId(), request.text(), List.of(), request.hitlResponse());
	}

	public record ChatRequest(String userId, String conversationId, String text,
							  HumanInTheLoopAdvisor.HitlResponse hitlResponse) {
	}

}
