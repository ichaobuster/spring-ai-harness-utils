package io.github.springai.harness.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.util.Assert;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * An advisor that limits the maximum number of agent loop iterations in a single
 * {@code call()} or {@code stream()} execution, preventing LLMs from entering an
 * infinite loop of executing tools without resolving the problem.
 * <p>
 * When the loop count reaches the configured limit, a {@link MaxAgentLoopException}
 * is thrown to immediately terminate the loop. The caller can catch this exception
 * and handle it appropriately (e.g., return a graceful error message to the user).
 * <p>
 * Usage:
 * <pre>{@code
 * chatClient.prompt()
 *     .advisors(MaxAgentLoopAdvisor.builder()
 *         .maxLoopRounds(15)
 *         .build()
 *         .advisorSpecConsumer())
 *     ...
 * }</pre>
 *
 * @author ichaobuster
 */
@Slf4j
public class MaxAgentLoopAdvisor implements BaseAdvisor {

	public static final int DEFAULT_MAX_LOOP_ROUNDS = 25;

	public static final String LOOP_ROUND_COUNTER_KEY = "agent_loop_round_counter";

	private final int maxLoopRounds;

	private final int order;

	private MaxAgentLoopAdvisor(int maxLoopRounds, int order) {
		this.maxLoopRounds = maxLoopRounds;
		this.order = order;
	}

	@Override
	public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
		if (request.prompt() == null || request.prompt().getInstructions() == null || request.prompt().getInstructions().isEmpty()) {
			return request;
		}

		Object counterObj = request.context().get(LOOP_ROUND_COUNTER_KEY);
		if (!(counterObj instanceof AtomicInteger counter)) {
			return request;
		}

		int currentRound = counter.incrementAndGet();
		if (currentRound > this.maxLoopRounds) {
			log.warn("Max agent loop limit exceeded ({}/{} rounds). Terminating execution.", currentRound, this.maxLoopRounds);
			throw new MaxAgentLoopException(this.maxLoopRounds);
		}

		return request;
	}

	@Override
	public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
		return response;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	public Consumer<ChatClient.AdvisorSpec> advisorSpecConsumer() {
		return advisorSpec -> {
			advisorSpec.advisors(this);
			advisorSpec.param(LOOP_ROUND_COUNTER_KEY, new AtomicInteger(0));
		};
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private int maxLoopRounds = DEFAULT_MAX_LOOP_ROUNDS;

		// After the default DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER
		private int order = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 50;

		public Builder maxLoopRounds(int maxLoopRounds) {
			Assert.isTrue(maxLoopRounds > 0, "maxLoopRounds must be greater than 0");
			this.maxLoopRounds = maxLoopRounds;
			return this;
		}

		public Builder order(int order) {
			this.order = order;
			return this;
		}

		public MaxAgentLoopAdvisor build() {
			return new MaxAgentLoopAdvisor(this.maxLoopRounds, this.order);
		}

	}

	/**
	 * 当 Agent 循环次数超过最大限制时抛出的异常。
	 * 调用方可以 catch 此异常，决定如何向用户呈现（返回错误信息、降级处理等）。
	 */
	public static class MaxAgentLoopException extends RuntimeException {

		private final int maxLoopRounds;

		public MaxAgentLoopException(int maxLoopRounds) {
			super("Agent loop exceeded the maximum allowed rounds: " + maxLoopRounds);
			this.maxLoopRounds = maxLoopRounds;
		}

		public int getMaxLoopRounds() {
			return maxLoopRounds;
		}

	}

}
