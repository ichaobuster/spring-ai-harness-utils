package io.github.springai.harness.advisor;

import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.Assert;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.ArrayList;
import java.util.List;

/**
 * Memory is retrieved added as a collection of messages to the prompt
 *
 * @author Christian Tzolov
 * @author Mark Pollack
 * @author Thomas Vitale
 * @author ichaobuster
 * @since 1.0.0
 */
public final class AdvancedMessageChatMemoryAdvisor implements BaseChatMemoryAdvisor {

	private final ChatMemory chatMemory;

	private final int order;

	private final Scheduler scheduler;

	private final boolean useStrict;

	private final ChatMemory chatMemoryForLog;

	private AdvancedMessageChatMemoryAdvisor(ChatMemory chatMemory, int order, Scheduler scheduler, boolean useStrict, ChatMemory chatMemoryForLog) {
		Assert.notNull(chatMemory, "chatMemory cannot be null");
		Assert.notNull(scheduler, "scheduler cannot be null");
		this.chatMemory = chatMemory;
		this.order = order;
		this.scheduler = scheduler;
		this.useStrict = useStrict;
		this.chatMemoryForLog = chatMemoryForLog;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	@Override
	public Scheduler getScheduler() {
		return this.scheduler;
	}

	@Override
	public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
		String conversationId = getConversationId(chatClientRequest.context());

		// 1. Retrieve the chat memory for the current conversation.
		List<Message> memoryMessages = this.chatMemory.get(conversationId);

		// 2. Advise the request messages list.
		List<Message> processedMessages = new ArrayList<>(memoryMessages);
		processedMessages.addAll(chatClientRequest.prompt().getInstructions());

		// 2.1. Ensure system message, if present, appears first in the list.
		for (int i = 0; i < processedMessages.size(); i++) {
			if (processedMessages.get(i) instanceof SystemMessage) {
				Message systemMessage = processedMessages.remove(i);
				processedMessages.add(0, systemMessage);
				break;
			}
		}

		// 3. Create a new request with the advised messages.
		ChatClientRequest processedChatClientRequest = chatClientRequest.mutate()
				.prompt(new Prompt(processedMessages, chatClientRequest.prompt().getOptions()))
				.build();

		// 4. Add the new user message to the conversation memory.
		Message userMessage = processedChatClientRequest.prompt().getLastUserOrToolResponseMessage();
		if (!useStrict || userMessage == processedMessages.get(processedMessages.size() - 1)) {
			this.chatMemory.add(conversationId, userMessage);
			if (this.chatMemoryForLog != null) this.chatMemoryForLog.add(conversationId, userMessage);
		}

		return processedChatClientRequest;
	}

	@Override
	public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
		List<Message> assistantMessages = new ArrayList<>();
		if (chatClientResponse.chatResponse() != null) {
			assistantMessages = chatClientResponse.chatResponse()
					.getResults()
					.stream()
					.map(g -> (Message) g.getOutput())
					.toList();
		}
		this.chatMemory.add(this.getConversationId(chatClientResponse.context()), assistantMessages);
		if (this.chatMemoryForLog != null) this.chatMemoryForLog.add(this.getConversationId(chatClientResponse.context()), assistantMessages);
		return chatClientResponse;
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
												 StreamAdvisorChain streamAdvisorChain) {
		// Get the scheduler from BaseAdvisor
		Scheduler scheduler = this.getScheduler();

		// Process the request with the before method
		return Mono.just(chatClientRequest)
				.publishOn(scheduler)
				.map(request -> this.before(request, streamAdvisorChain))
				.flatMapMany(streamAdvisorChain::nextStream)
				.transform(flux -> new ChatClientMessageAggregator().aggregateChatClientResponse(flux,
						response -> this.after(response, streamAdvisorChain)));
	}

	public static Builder builder(ChatMemory chatMemory) {
		return new Builder(chatMemory);
	}

	public static final class Builder {

		private int order = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;

		private Scheduler scheduler = BaseAdvisor.DEFAULT_SCHEDULER;

		private final ChatMemory chatMemory;

		private boolean useStrict = false;

		private ChatMemory chatMemoryForLog;

		private Builder(ChatMemory chatMemory) {
			Assert.notNull(chatMemory, "chatMemory cannot be null");
			this.chatMemory = chatMemory;
		}

		/**
		 * Set the order.
		 *
		 * @param order the order
		 * @return the builder
		 */
		public Builder order(int order) {
			this.order = order;
			return this;
		}

		public Builder scheduler(Scheduler scheduler) {
			this.scheduler = scheduler;
			return this;
		}

		/**
		 * 是否启用严格模式<br/>
		 * 严格模式下，before 中仅最后一条 message 类型为 user 或 tool 时才做 memory 记录
		 *
		 * @return
		 */
		public Builder useStrict(boolean useStrict) {
			this.useStrict = useStrict;
			return this;
		}

		/**
		 * 记录完整 chat log 的 ChatMemory，不参与 session 读取，仅用于记录
		 */
		public Builder chatMemoryForLog(ChatMemory chatMemoryForLog) {
			this.chatMemoryForLog = chatMemoryForLog;
			return this;
		}

		/**
		 * Build the advisor.
		 *
		 * @return the advisor
		 */
		public AdvancedMessageChatMemoryAdvisor build() {
			return new AdvancedMessageChatMemoryAdvisor(this.chatMemory, this.order, this.scheduler, this.useStrict, this.chatMemoryForLog);
		}

	}

}