package io.github.springai.harness.advisor;

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
import org.springframework.ai.util.json.JsonParser;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ToolArgsCompactAdvisor 模仿 AgentScope Java 中 truncateArgs 能力，压缩类型为String且长度大于一定值的工具参数
 *
 * @author ichaobuster
 */

@Slf4j
public class ToolArgsCompactAdvisor implements BaseChatMemoryAdvisor {

	/**
	 * 已被清除的 tool args 内容替换为此文本
	 */
	public static final String DEFAULT_TRUNCATION_TEXT = "...(argument truncated)";

	public static final int DEFAULT_MAX_ARG_LENGTH = 2000;

	private static final int ARG_PREVIEW_SIZE = 20;

	// ==================== 配置字段 ====================

	/**
	 * 数量触发阈值：当 assistant messages 总数超过此值时触发清除。
	 */
	private final int triggerMessages;

	/**
	 * 保留最近的 N 个 assistant messages 不做压缩。
	 */
	private final int keepRecent;

	/**
	 * 不进行压缩的工具参数长度，默认为2000
	 */
	private final int maxArgLength;

	/**
	 * 已被清除的 tool args 内容替换为此文本
	 */
	private String truncationText;

	private final int order;

	private final ChatMemory chatMemory;

	// ==================== 构造器 ====================

	/**
	 * 全参数构造器。
	 */
	public ToolArgsCompactAdvisor(int triggerMessages,
								  int keepRecent,
								  int maxArgLength,
								  String truncationText,
								  int order,
								  ChatMemory chatMemory) {
		Assert.hasText(truncationText, "truncationText must not be empty");

		this.triggerMessages = triggerMessages;
		this.keepRecent = Math.max(1, keepRecent); // 至少保留 1 个
		this.maxArgLength = maxArgLength;
		this.truncationText = truncationText;
		this.order = order;
		this.chatMemory = chatMemory;
	}


	// ==================== Builder ====================

	/**
	 * 创建 Builder 以便流式配置。
	 */
	public static Builder builder() {
		return new Builder();
	}

	@Override
	public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
		if (request.prompt() == null || request.prompt().getInstructions() == null) {
			return request;
		}

		List<Message> messages = request.prompt().getInstructions();

		if (!shouldTruncateArgs(messages)) {
			return request;
		}

		Integer cutoff = findCutoffIndex(messages);
		if (cutoff == null) {
			return request;
		}

		boolean anyModified = false;
		List<Message> result = new ArrayList<>(messages.size());
		for (int i = 0; i < messages.size(); i++) {
			Message msg = messages.get(i);
			if (i < cutoff && msg.getMessageType() == MessageType.ASSISTANT && msg instanceof AssistantMessage am) {
				Message truncated = truncateToolUseArgs(am);
				result.add(truncated);
				if (truncated != msg) {
					anyModified = true;
				}
			} else {
				result.add(msg);
			}
		}

		if (anyModified) {
			log.info("Arg truncation applied to messages before index {}", cutoff);
			return request.mutate()
					.prompt(new Prompt(result, request.prompt().getOptions()))
					.build();
		}

		return request;
	}

	private boolean shouldTruncateArgs(List<Message> messages) {
		return messages.stream()
				.filter(msg -> msg.getMessageType() == MessageType.ASSISTANT && msg instanceof AssistantMessage)
				.count() > this.triggerMessages;
	}

	private Integer findCutoffIndex(List<Message> messages) {
		int keeped = 0;
		for (int i = messages.size() - 1; i >= 0; i--) {
			Message msg = messages.get(i);
			if (msg.getMessageType() == MessageType.ASSISTANT && msg instanceof AssistantMessage) {
				keeped++;
				if (keeped >= this.keepRecent) {
					return i;
				}
			}
		}
		return null;
	}

	/**
	 * Returns a copy of the message with large {@code AssistantMessage.ToolCall} argument values shortened.
	 * If no argument exceeds the limit, the original message reference is returned unchanged.
	 */
	private AssistantMessage truncateToolUseArgs(AssistantMessage msg) {
		List<AssistantMessage.ToolCall> toolCalls = msg.getToolCalls();
		if (toolCalls == null || toolCalls.isEmpty()) {
			return msg;
		}

		boolean anyModified = false;
		List<AssistantMessage.ToolCall> newToolCalls = new ArrayList<>(toolCalls.size());
		for (AssistantMessage.ToolCall toolCall : toolCalls) {
			AssistantMessage.ToolCall truncated = truncateToolCall(toolCall);
			newToolCalls.add(truncated);
			if (truncated != toolCall) {
				anyModified = true;
			}
		}

		if (!anyModified) {
			return msg;
		}
		return AssistantMessage.builder()
				.content(msg.getText())
				.media(msg.getMedia())
				.toolCalls(newToolCalls)
				.properties(msg.getMetadata())
				.build();
	}

	/**
	 * Returns a copy of the {@code AssistantMessage.ToolCall} with large string arg values truncated,
	 * or the original if no truncation was needed.
	 */
	private AssistantMessage.ToolCall truncateToolCall(AssistantMessage.ToolCall tc) {
		if (!StringUtils.hasText(tc.arguments())) {
			return tc;
		}

		try {
			Map<String, Object> input = JsonParser.fromJson(tc.arguments(), Map.class);
			if (input == null || input.isEmpty()) {
				return tc;
			}

			boolean anyModified = false;
			Map<String, Object> newInput = new HashMap<>(input);
			for (Map.Entry<String, Object> entry : input.entrySet()) {
				if (entry.getValue() instanceof String s && s.length() > this.maxArgLength) {
					newInput.put(entry.getKey(), s.substring(0, ARG_PREVIEW_SIZE) + truncationText);
					anyModified = true;
				}
			}

			if (!anyModified) {
				return tc;
			}
			return new AssistantMessage.ToolCall(tc.id(), tc.type(), tc.name(), JsonParser.toJson(newInput));
		} catch (IllegalStateException e) {
			log.error(e.getMessage(), e);
			return tc;
		}
	}

	@Override
	public ChatClientResponse after(ChatClientResponse response, AdvisorChain advisorChain) {
		return response;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	public static class Builder {
		private int triggerMessages = 10;
		private int keepRecent = 5;
		private int maxArgLength = DEFAULT_MAX_ARG_LENGTH;

		private String truncationText = DEFAULT_TRUNCATION_TEXT;

		// After the default DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER
		private int order = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 120;

		private ChatMemory chatMemory;

		/**
		 * 数量触发阈值。当 assistant messages 总数超过此值时触发清除。
		 * 默认 10。
		 */
		public Builder triggerMessages(int triggerMessages) {
			this.triggerMessages = triggerMessages;
			return this;
		}

		/**
		 * 保留最近 N 个 assistant messages 不做压缩。
		 * 默认 5。
		 */
		public Builder keepRecent(int keepRecent) {
			this.keepRecent = keepRecent;
			return this;
		}

		/**
		 * 不进行压缩的工具参数长度，默认为2000
		 */
		public Builder maxArgLength(int maxArgLength) {
			this.maxArgLength = maxArgLength;
			return this;
		}

		/**
		 * 已被清除的 tool args 内容替换为此文本
		 */
		public Builder truncationText(String truncationText) {
			this.truncationText = truncationText;
			return this;
		}

		/**
		 * Advisor 执行顺序。
		 */
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

		public ToolArgsCompactAdvisor build() {
			return new ToolArgsCompactAdvisor(
					triggerMessages,
					keepRecent,
					maxArgLength,
					truncationText,
					order,
					chatMemory
			);
		}
	}
}
