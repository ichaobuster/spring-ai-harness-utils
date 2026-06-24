package io.github.springai.harness.advisor;

import io.github.springai.harness.util.ChatMemoryUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ClearThinkingAdvisor 用于清除 AssistantMessage 中的思考内容（</think> 之前的内容）。
 * <p>
 * 规则：
 * <p>
 * <ol>
 *   <li>只有包含 </think> 标记的 AssistantMessage 才是清理候选。</li>
 *   <li>保留最后 N 条 AssistantMessage 的思考内容。</li>
 *   <li>基于时间清理：如果最后一条 AssistantMessage 的时间与当前时间间隔超过阈值，则触发清理。</li>
 *   <li>如果清理后内容为空，如果 message 包含 tool_calls 或 media，保留消息但内容设为空字符串；否则从消息列表中剔除该消息。</li>
 * </ol>
 * <b>与其他 Advisor 的关系</b>：
 *  <ul>
 *    <li>AutoCompact 应在 {@link AutoCompactAdvisor} 之前运行（order 更小）</li>
 *  </ul>
 */
@Slf4j
public class ClearThinkingAdvisor implements BaseChatMemoryAdvisor {

	private static final String THINK_TAG = "</think>";

	private final int keepRecent;

	private final int order;

	private final ChatMemory chatMemory;

	public ClearThinkingAdvisor(int keepRecent, int order, ChatMemory chatMemory) {
		this.keepRecent = Math.max(1, keepRecent);
		this.order = order;
		this.chatMemory = chatMemory;
	}

	@Override
	public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
		if (request.prompt() == null || request.prompt().getInstructions() == null) {
			return request;
		}

		List<Message> messages = request.prompt().getInstructions();
		List<Integer> indexesOfCandidates = collectCandidates(messages);

		if (indexesOfCandidates.isEmpty()) {
			return request;
		}
		
		if (indexesOfCandidates.size() > keepRecent) {
			return applyClearing(request, messages, indexesOfCandidates);
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

	private List<Integer> collectCandidates(List<Message> messages) {
		List<Integer> entries = new ArrayList<>();
		for (int i = 0; i < messages.size(); i++) {
			Message msg = messages.get(i);
			if (msg.getMessageType() == MessageType.ASSISTANT && msg instanceof AssistantMessage am) {
				String content = am.getText();
				if (content != null && content.contains(THINK_TAG)) {
					entries.add(i);
				}
			}
		}
		return entries;
	}

	private ChatClientRequest applyClearing(ChatClientRequest request, List<Message> messages, List<Integer> indexesOfCandidates) {
		int clearUntil = Math.max(0, indexesOfCandidates.size() - keepRecent);

		Set<Integer> indexesToClear = new HashSet<>();
		for (int i = 0; i < clearUntil; i++) {
			indexesToClear.add(indexesOfCandidates.get(i));
		}

		if (indexesToClear.isEmpty()) {
			return request;
		}

		List<Message> updatedMessages = new ArrayList<>();
		for (int i = 0; i < messages.size(); i++) {
			Message msg = messages.get(i);
			if (indexesToClear.contains(i)) {
				AssistantMessage am = (AssistantMessage) msg;
				String content = am.getText();
				int tagIndex = content.lastIndexOf(THINK_TAG);
				String newContent = content.substring(tagIndex + THINK_TAG.length());

				if (!StringUtils.hasText(newContent)) {
					boolean hasToolCalls = am.hasToolCalls();
					boolean hasMedia = am.getMedia() != null && !am.getMedia().isEmpty();

					if (hasToolCalls || hasMedia) {
						updatedMessages.add(AssistantMessage.builder()
								.content("")
								.properties(am.getMetadata())
								.toolCalls(am.getToolCalls())
								.media(am.getMedia())
								.build());
					} else {
						log.info("Removing AssistantMessage at index {} because it's empty after clearing thinking", i);
					}
				} else {
					updatedMessages.add(AssistantMessage.builder()
							.content(newContent)
							.properties(am.getMetadata())
							.toolCalls(am.getToolCalls())
							.media(am.getMedia())
							.build());
				}
			} else {
				updatedMessages.add(msg);
			}
		}

		ChatMemoryUtil.replaceChatMemoryMessages(this.chatMemory, request.context(), updatedMessages);

		return request.mutate()
				.prompt(new Prompt(updatedMessages, request.prompt().getOptions()))
				.build();
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {
		private int keepRecent = 3;

		// After the default DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER
		private int order = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 140;

		private ChatMemory chatMemory;

		public Builder keepRecent(int keepRecent) {
			this.keepRecent = keepRecent;
			return this;
		}

		public Builder order(int order) {
			this.order = order;
			return this;
		}

		/**
		 * 如果设置了 chatMemory 实例，则将在发生压缩操作后将压缩后结果更新到 chatMemory 中
		 */
		public Builder chatMemory(ChatMemory chatMemory) {
			this.chatMemory = chatMemory;
			return this;
		}

		public ClearThinkingAdvisor build() {
			return new ClearThinkingAdvisor(keepRecent, order, chatMemory);
		}
	}
}
