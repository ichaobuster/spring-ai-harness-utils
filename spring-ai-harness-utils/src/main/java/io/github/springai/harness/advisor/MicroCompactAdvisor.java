package io.github.springai.harness.advisor;

import io.github.springai.harness.util.ChatMemoryUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.*;

/**
 * Spring AI Advisor 实现，移植自 Claude Code 泄漏源码的 src/services/compact/microCompact.ts 的 microcompact 逻辑。
 * <p>
 * <b>核心功能</b>：在发送给 LLM 之前，对历史消息中较旧的、可压缩工具的返回结果进行内容清除，
 * 用占位符 {@value #CLEARED_MESSAGE} 替换原始内容，以减少 token 消耗。
 * <p>
 * <b>数触发机制为基于数量触发（Count-based）</b>：当可压缩工具结果总数超过 {@code triggerThreshold} 时触发，
 * 保留最近的 {@code keepRecent} 个，清除更早的。</li>
 * <p>
 * <b>可压缩工具</b>：通过 {@link #compactableToolNames} 指定。只有返回这些工具结果的
 * ToolResponseMessage 才会被清除。默认的工具集合参照 TS 中的 {@code COMPACTABLE_TOOLS}。
 * <p>
 * <b>设计要点</b>：
 * <ul>
 *   <li>本 Advisor 是无状态的（Stateless），不在 JVM 内存中维持跨请求状态。
 *       每次 {@code before()} 调用时从消息列表中重新计算需要清除的 tool results。</li>
 *   <li>与 {@link ToolResultBudgetAdvisor} 互补：ToolResultBudget 基于字符大小做"大文件外置"，
 *       MicroCompact 基于数量/时间做"旧结果清除"。两者可以同时使用、互不干扰。</li>
 *   <li>MicroCompact 应在 ToolResultBudget 之后运行（order 更大）。</li>
 * </ul>
 */
@Slf4j
public class MicroCompactAdvisor implements BaseChatMemoryAdvisor {

	/**
	 * 已被清除的 tool result 内容替换为此消息（与 TS 的 TIME_BASED_MC_CLEARED_MESSAGE 对齐）
	 */
	public static final String CLEARED_MESSAGE = "[Old tool result content cleared]";

	// ==================== 配置字段 ====================

	/**
	 * 可压缩的工具名称集合
	 */
	private final Set<String> compactableToolNames;

	/**
	 * 数量触发阈值：当可压缩 tool result 总数超过此值时触发清除。
	 * 对应 TS cachedMC 的 triggerThreshold。
	 */
	private final int triggerThreshold;

	/**
	 * 保留最近的 N 个可压缩 tool result。
	 * 对应 TS 的 keepRecent，最小为 1。
	 */
	private final int keepRecent;

	/**
	 * 不进行压缩的工具结果长度，默认为0，即不论结果多长都压缩
	 */
	private final long keepResultLength;

	private final int order;

	private final ChatMemory chatMemory;

	// ==================== 构造器 ====================

	/**
	 * 全参数构造器。
	 *
	 * @param compactableToolNames 可压缩的工具名称集合
	 * @param triggerThreshold     数量触发阈值
	 * @param keepRecent           保留最近的 N 个
	 * @param order                Advisor 执行顺序
	 */
	public MicroCompactAdvisor(Set<String> compactableToolNames,
							   int triggerThreshold,
							   int keepRecent,
							   long keepResultLength,
							   int order,
							   ChatMemory chatMemory) {
		this.compactableToolNames = Set.copyOf(compactableToolNames);
		this.triggerThreshold = triggerThreshold;
		this.keepRecent = Math.max(1, keepRecent); // 至少保留 1 个
		this.keepResultLength = keepResultLength;
		this.order = order;
		this.chatMemory = chatMemory;
	}

	// ==================== BaseAdvisor 实现 ====================

	@Override
	public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
		if (request.prompt() == null || request.prompt().getInstructions() == null) {
			return request;
		}

		List<Message> messages = request.prompt().getInstructions();

		// --- 第一步：收集所有可压缩的 tool_use ID ---
		// 遍历 Assistant 消息的 toolCalls，收集 tool name 在 compactableToolNames 中的 ID。
		// 在 Spring AI 中，AssistantMessage 上有 toolCalls 列表，
		// ToolResponseMessage 中有对应的 ToolResponse(id, name, data)。
		// 我们从 ToolResponseMessage 中按出现顺序收集 compactable 的 tool response ID。
		List<ToolResponseMessage.ToolResponse> compactableToolResponses = collectCompactableToolResponses(messages);

		if (compactableToolResponses.isEmpty()) {
			return request;
		}

		// --- 第二步：判断触发条件 & 确定要清除的 ID 集合 ---
		Set<String> clearIds;

		// 数量触发
		if (compactableToolResponses.size() > triggerThreshold) {
			clearIds = computeClearSetByKeepRecent(compactableToolResponses, this.keepRecent);
			if (!clearIds.isEmpty()) {
				log.info("Count-based trigger fired: total={} > threshold={}, " +
								"clearing {} tool results, keeping last {}",
						compactableToolResponses.size(), triggerThreshold, clearIds.size(),
						compactableToolResponses.size() - clearIds.size());
				return applyClearing(request, messages, clearIds);
			}
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

	// ==================== 核心逻辑 ====================

	/**
	 * 从消息列表中按出现顺序收集可压缩的 tool response 条目。
	 * <p>
	 * 只收集 tool name 在 {@link #compactableToolNames} 中、
	 * 且内容尚未被清除（不等于 {@value #CLEARED_MESSAGE}）的条目。
	 */
	private List<ToolResponseMessage.ToolResponse> collectCompactableToolResponses(List<Message> messages) {
		List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
		for (Message message : messages) {
			if (message.getMessageType() == MessageType.TOOL && message instanceof ToolResponseMessage trm) {
				for (ToolResponseMessage.ToolResponse tr : trm.getResponses()) {
					if (compactableToolNames.contains(tr.name()) && Optional.ofNullable(tr.responseData()).orElse("").length() > this.keepResultLength) {
						toolResponses.add(tr);
					}
				}
			}
		}
		return toolResponses;
	}

	/**
	 * 计算需要清除的 ID 集合：保留最近 keepRecent 个，清除其余的。
	 * <p>
	 * 对应 TS 的：
	 * <pre>
	 * const keepSet = new Set(compactableIds.slice(-keepRecent))
	 * const clearSet = new Set(compactableIds.filter(id => !keepSet.has(id)))
	 * </pre>
	 * <p>
	 * 只选择内容尚未被清除的条目。已经是 {@value #CLEARED_MESSAGE} 的不再重复处理。
	 */
	private Set<String> computeClearSetByKeepRecent(List<ToolResponseMessage.ToolResponse> toolResponses, int keepRecent) {
		// 保留最后 keepRecent 个的 ID
		int totalSize = toolResponses.size();
		int keepFromIndex = Math.max(0, totalSize - keepRecent);
		Set<String> keepIds = new HashSet<>();
		for (int i = keepFromIndex; i < totalSize; i++) {
			keepIds.add(toolResponses.get(i).id());
		}

		// 其中不在 keepIds 中、且内容未被清除的，加入 clearSet
		Set<String> clearIds = new LinkedHashSet<>();
		for (ToolResponseMessage.ToolResponse toolResponse : toolResponses) {
			if (!keepIds.contains(toolResponse.id())) {
				String data = toolResponse.responseData();
				if (data != null && !CLEARED_MESSAGE.equals(data)) {
					clearIds.add(toolResponse.id());
				}
			}
		}
		return clearIds;
	}

	/**
	 * 对消息列表执行内容清除：将 clearIds 中的 tool response 内容替换为 {@value #CLEARED_MESSAGE}。
	 * <p>
	 * 对应 TS 中 {@code maybeTimeBasedMicrocompact} 的 map 逻辑：
	 * <pre>
	 * if (block.type === 'tool_result' && clearSet.has(block.tool_use_id)
	 *     && block.content !== TIME_BASED_MC_CLEARED_MESSAGE) {
	 *   return { ...block, content: TIME_BASED_MC_CLEARED_MESSAGE }
	 * }
	 * </pre>
	 */
	private ChatClientRequest applyClearing(ChatClientRequest request,
											List<Message> messages,
											Set<String> clearIds) {
		List<Message> updatedMessages = new ArrayList<>(messages.size());
		int tokensSaved = 0;

		for (Message message : messages) {
			if (message.getMessageType() == MessageType.TOOL && message instanceof ToolResponseMessage trm) {
				boolean needsReplace = trm.getResponses().stream().anyMatch(r -> clearIds.contains(r.id()));
				if (needsReplace) {
					List<ToolResponseMessage.ToolResponse> newResponses = new ArrayList<>();
					for (ToolResponseMessage.ToolResponse tr : trm.getResponses()) {
						if (clearIds.contains(tr.id())) {
							String data = tr.responseData();
							if (data != null) {
								tokensSaved += estimateTokens(data);
							}
							newResponses.add(new ToolResponseMessage.ToolResponse(
									tr.id(), tr.name(), CLEARED_MESSAGE));
						} else {
							newResponses.add(tr);
						}
					}
					updatedMessages.add(ToolResponseMessage.builder()
							.responses(newResponses)
							.metadata(trm.getMetadata())
							.build());
				} else {
					updatedMessages.add(message);
				}
			} else {
				updatedMessages.add(message);
			}
		}

		if (tokensSaved > 0) {
			log.info("Cleared {} tool results, estimated ~{} tokens saved",
					clearIds.size(), tokensSaved);
			ChatMemoryUtil.replaceChatMemoryMessages(this.chatMemory, request.context(), updatedMessages);
		}

		return request.mutate()
				.prompt(new Prompt(updatedMessages, request.prompt().getOptions()))
				.build();
	}

	/**
	 * 粗略估算文本的 token 数（字符数 / 4，再乘以 4/3 保守系数）。
	 * <p>
	 * 对应 TS 的 {@code roughTokenCountEstimation(content) * 4/3}。
	 */
	private int estimateTokens(String content) {
		if (content == null || content.isEmpty()) {
			return 0;
		}
		return (int) Math.ceil(content.length() / 4.0 * (4.0 / 3.0));
	}

	// ==================== Builder ====================

	/**
	 * 创建 Builder 以便流式配置。
	 */
	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {
		private Set<String> compactableToolNames = new HashSet<>();
		private int triggerThreshold = 10;
		private int keepRecent = 5;
		private long keepResultLength = 0;

		// After the default DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER
		private int order = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 120;

		private ChatMemory chatMemory;

		/**
		 * 添加一个可压缩的工具名称。
		 */
		public Builder addCompactableToolName(String name) {
			this.compactableToolNames.add(name);
			return this;
		}

		/**
		 * 设置可压缩的工具名称集合。
		 */
		public Builder compactableToolNames(Set<String> names) {
			this.compactableToolNames = new HashSet<>(names);
			return this;
		}

		/**
		 * 设置可压缩的工具名称集合（varargs 形式）。
		 */
		public Builder compactableToolNames(String... names) {
			this.compactableToolNames = new HashSet<>(Arrays.asList(names));
			return this;
		}

		/**
		 * 数量触发阈值。当可压缩 tool result 总数超过此值时触发清除。
		 * 默认 10。
		 */
		public Builder triggerThreshold(int triggerThreshold) {
			this.triggerThreshold = triggerThreshold;
			return this;
		}

		/**
		 * 保留最近 N 个可压缩 tool result，最小为 1。
		 * 默认 5。
		 */
		public Builder keepRecent(int keepRecent) {
			this.keepRecent = keepRecent;
			return this;
		}

		/**
		 * 不进行压缩的工具结果长度，默认为0，即不论结果多长都压缩
		 */
		public Builder keepResultLength(long keepResultLength) {
			this.keepResultLength = keepResultLength;
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

		public MicroCompactAdvisor build() {
			return new MicroCompactAdvisor(
					compactableToolNames,
					triggerThreshold,
					keepRecent,
					keepResultLength,
					order,
					chatMemory
			);
		}
	}
}
