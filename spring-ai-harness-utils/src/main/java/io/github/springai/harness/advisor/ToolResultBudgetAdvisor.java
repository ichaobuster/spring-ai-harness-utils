package io.github.springai.harness.advisor;

import io.github.springai.harness.tool.ToolResultBudgetTool;
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
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.*;

/**
 * An advisor that implements an applyToolResultBudget capability using Redis.
 * Iterates through historical prompt messages, finds overly large Tool execution results,
 * truncates them and saves the full result to Redis with a TTL.
 * Instructs the LLM to use a ToolResultBudgetTool to retrieve the original output via ID.
 * <p>
 * Spring AI Advisor 实现，移植自 Claude Code 泄漏源码 src/utils/toolResultStorage.ts 的 applyToolResultBudget 能力。<br/>
 * Claude Code 执行过程 applyToolResultBudget -> snipCompact（未开启） -> microcompact -> contextCollapse（未开启） -> autocompact (参考 query.ts 369 - 468 行)<br/>
 * 我们的实现过程为 applyToolResultBudget -> microcompact -> autocompact<br/>
 * 本 Advisor 实现 applyToolResultBudget 的能力
 * <p>
 * 核心设计（与 TS 实现对齐）：
 * <ul>
 *   <li><b>Per-message 聚合预算</b>：以 Assistant 消息为分隔，将连续的 Tool 消息分组，
 *       只有当某一组的总字符数超过 {@code maxPerGroupBudgetChars} 时，才从最大的开始压缩存入 Redis。</li>
 *   <li><b>单条限制</b>：单个 tool result 超过 {@code maxSingleResultChars} 字符时，
 *       无论聚合预算是否超限，都会被压缩。</li>
 *   <li><b>已压缩内容识别</b>：通过 {@code <persisted-output>} 标签前缀判断内容是否已被压缩过，
 *       已压缩的内容不会被再次处理，其长度计入 frozen 开销。</li>
 *   <li><b>skipToolNames</b>：排除特定工具（如 ToolResultBudgetTool）的返回值不被压缩，
 *       避免循环依赖。</li>
 * </ul>
 */
@Slf4j
public class ToolResultBudgetAdvisor implements BaseChatMemoryAdvisor {

	private static final Integer DEFAULT_PREVIEW_SIZE_CHARS = 2000;

	private static final String PERSISTED_OUTPUT_TAG = "<persisted-output>";

	/**
	 * 对应 TS 的 DEFAULT_MAX_RESULT_SIZE_CHARS = 50_000
	 */
	public static final int DEFAULT_MAX_SINGLE_RESULT_CHARS = 50_000;

	/**
	 * 对应 TS 的 MAX_TOOL_RESULTS_PER_MESSAGE_CHARS = 200_000
	 */
	public static final int DEFAULT_MAX_PER_GROUP_BUDGET_CHARS = 200_000;

	/**
	 * 默认 TTL：1 小时
	 */
	public static final Duration DEFAULT_TTL = Duration.ofHours(1);

	private final StringRedisTemplate redisTemplate;
	/**
	 * 单个 tool result 超过此值立即压缩存入 Redis
	 */
	private final int maxSingleResultChars;
	/**
	 * 同一组（由 Assistant 分隔）内所有 tool results 聚合字符数的预算上限
	 */
	private final int maxPerGroupBudgetChars;

	/**
	 * 预览文本长度
	 */
	private final int previewSize;

	private final Duration ttl;
	private final int order;

	private final ChatMemory chatMemory;

	/**
	 * 不参与 budget 压缩的工具名称（如 ToolResultBudgetTool / Read 等）
	 */
	private final Set<String> skipToolNames;

	/**
	 * @param redisTemplate          StringRedisTemplate 实例
	 * @param maxSingleResultChars   单条 tool result 超过此值即压缩
	 * @param maxPerGroupBudgetChars 同 Assistant 分隔组内聚合字符数预算
	 */
	public ToolResultBudgetAdvisor(StringRedisTemplate redisTemplate,
								   int maxSingleResultChars,
								   int maxPerGroupBudgetChars,
								   Duration ttl,
								   Set<String> skipToolNames,
								   int previewSize,
								   int order,
								   ChatMemory chatMemory) {
		Assert.notNull(redisTemplate, "redisTemplate cannot be null");
		this.redisTemplate = redisTemplate;
		this.maxSingleResultChars = maxSingleResultChars;
		this.maxPerGroupBudgetChars = maxPerGroupBudgetChars;
		this.ttl = ttl;
		this.skipToolNames = skipToolNames;
		this.previewSize = previewSize <= 0 ? DEFAULT_PREVIEW_SIZE_CHARS : previewSize;
		this.order = order;
		this.chatMemory = chatMemory;
	}

	@Override
	public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
		if (request.prompt() == null || request.prompt().getInstructions() == null) {
			return request;
		}

		ChatOptions chatOptions = request.prompt().getOptions();
		// 注入 ToolResultBudgetTool
		injectToolResultBudgetTool(chatOptions);

		List<Message> instructions = request.prompt().getInstructions();

		// --- 第一步：分组（以 Assistant 消息为栅栏） ---
		List<ToolGroup> toolGroups = partitionIntoGroups(instructions);

		// --- 第二步：对每组执行 budget 压缩决策 ---
		Map<String, String> pendingReplacements = new LinkedHashMap<>();
		for (ToolGroup group : toolGroups) {
			processGroup(group, pendingReplacements);
		}
		if (pendingReplacements.isEmpty()) {
			return request;
		}
		log.info("Budget exceeded, replacing {} tool results with Redis references", pendingReplacements.size());

		// --- 第三步：组装替换后的消息列表 ---
		List<Message> updatedMessages = applyReplacements(instructions, pendingReplacements);
		ChatMemoryUtil.replaceChatMemoryMessages(this.chatMemory, request.context(), updatedMessages);
		return request.mutate()
				.prompt(new Prompt(updatedMessages, chatOptions))
				.build();
	}

	private void injectToolResultBudgetTool(ChatOptions chatOptions) {
		if (chatOptions instanceof ToolCallingChatOptions toolOptions) {
			List<ToolCallback> toolCallbacks = toolOptions.getToolCallbacks();
			Set<String> existingNames = toolCallbacks.stream()
					.map(tc -> tc.getToolDefinition().name())
					.collect(java.util.stream.Collectors.toSet());
			if (!existingNames.contains(ToolResultBudgetTool.TOOL_NAME)) {
				toolCallbacks.add(ToolResultBudgetTool.createToolCallback(redisTemplate));
			}
		}
	}

	/**
	 * 以 Assistant 消息为分隔栅栏，将连续的 TOOL 消息分组。
	 * 对应 TS 的 collectCandidatesByMessage —— 只有 Assistant 才创建 wire-level boundary。
	 */
	private List<ToolGroup> partitionIntoGroups(List<Message> messages) {
		List<ToolGroup> groups = new ArrayList<>();
		List<ToolResponseMessage.ToolResponse> currentCandidates = new ArrayList<>();
		Set<String> seenAssistantIds = new HashSet<>();

		for (Message message : messages) {
			if (message.getMessageType() == MessageType.TOOL && message instanceof ToolResponseMessage trm) {
				currentCandidates.addAll(trm.getResponses());
			} else if (message.getMessageType() == MessageType.ASSISTANT) {
				String assistantId = extractAssistantId(message);
				if (assistantId != null && !seenAssistantIds.contains(assistantId)) {
					if (!currentCandidates.isEmpty()) {
						groups.add(new ToolGroup(currentCandidates));
						currentCandidates = new ArrayList<>();
					}
					seenAssistantIds.add(assistantId);
				}
			}
		}
		if (!currentCandidates.isEmpty()) {
			groups.add(new ToolGroup(currentCandidates));
		}
		return groups;
	}

	/**
	 * 对单个组执行 budget 决策。
	 * <p>
	 * 状态判断完全基于 content 内容特征，而非外部状态存储：
	 * <ul>
	 *   <li>content 以 {@code <persisted-output>} 开头 → 已被压缩过，计入 frozen 开销，跳过</li>
	 *   <li>工具名在 skipToolNames 中 → 跳过</li>
	 *   <li>其余 → 作为 fresh candidate 参与预算竞争</li>
	 * </ul>
	 */
	private void processGroup(ToolGroup group, Map<String, String> pendingReplacements) {
		int frozenSize = 0;
		List<ToolResponseMessage.ToolResponse> freshCandidates = new ArrayList<>();

		for (ToolResponseMessage.ToolResponse candidate : group.candidates) {
			String content = candidate.responseData();

			// 已被压缩过的内容（上一轮 advisor 的产物），计入 frozen 开销但不再处理
			if (content != null && content.startsWith(PERSISTED_OUTPUT_TAG)) {
				frozenSize += content.length();
				continue;
			}

			// 跳过 skipToolNames 中列出的工具
			if (skipToolNames.contains(candidate.name())) {
				continue;
			}

			// 原始内容，作为 fresh candidate
			freshCandidates.add(candidate);
		}

		// 计算 fresh 总大小
		int freshSize = freshCandidates.stream()
				.mapToInt(c -> c.responseData() != null ? c.responseData().length() : 0)
				.sum();

		// --- 选择需要压缩的 candidates ---
		// 两个维度独立判断：
		//   1. 单条超过 maxSingleResultChars → 无条件压缩
		//   2. 组聚合超过 maxPerGroupBudgetChars → 从大到小压缩直到预算内
		Set<String> selectedIds = new HashSet<>();
		int remaining = frozenSize + freshSize;

		// 维度 1：单条超限，无条件入选
		for (ToolResponseMessage.ToolResponse candidate : freshCandidates) {
			String data = candidate.responseData();
			if (data != null && data.length() > maxSingleResultChars) {
				log.debug("Single result exceeds maxSingleResultChars ({} > {}), marking for compression: {}", data.length(), maxSingleResultChars, candidate.id());
				selectedIds.add(candidate.id());
				remaining -= data.length();
			}
		}

		// 维度 2：聚合超预算，从大到小补选
		if (remaining > maxPerGroupBudgetChars) {
			log.debug("Group budget exceeded (frozen: {}, fresh: {}, total: {} > {}), selecting largest to compress", frozenSize, freshSize, frozenSize + freshSize, maxPerGroupBudgetChars);
			freshCandidates.sort((a, b) -> {
				int sizeA = a.responseData() != null ? a.responseData().length() : 0;
				int sizeB = b.responseData() != null ? b.responseData().length() : 0;
				return Integer.compare(sizeB, sizeA);
			});

			for (ToolResponseMessage.ToolResponse candidate : freshCandidates) {
				if (remaining <= maxPerGroupBudgetChars) break;
				if (selectedIds.contains(candidate.id())) continue;
				String data = candidate.responseData();
				int dataSize = data != null ? data.length() : 0;
				selectedIds.add(candidate.id());
				remaining -= dataSize;
			}
		}

		// 对选中的执行压缩：存入 Redis + 生成替换文本
		for (ToolResponseMessage.ToolResponse candidate : freshCandidates) {
			String id = candidate.id();
			if (!selectedIds.contains(id)) continue;

			String data = candidate.responseData();
			if (data == null || data.isEmpty()) continue;

			String redisKey = ToolResultBudgetTool.REDIS_KEY_PREFIX + id;
			redisTemplate.opsForValue().set(redisKey, data, ttl);

			String preview = getPreview(data);
			String replacementText = buildReplacementText(redisKey, data, preview);
			pendingReplacements.put(id, replacementText);

			log.info("Compressed and saved tool result for tool '{}' to Redis. Key: {}, Original size: {}, Preview size: {}", candidate.name(), redisKey, data.length(), preview.length());
		}
	}

	/**
	 * 根据 pendingReplacements 映射，在原消息列表中替换 ToolResponseMessage 的内容。
	 */
	private List<Message> applyReplacements(List<Message> messages, Map<String, String> replacementMap) {
		List<Message> result = new ArrayList<>(messages.size());
		for (Message message : messages) {
			if (message.getMessageType() == MessageType.TOOL && message instanceof ToolResponseMessage trm) {
				boolean needsReplace = trm.getResponses().stream()
						.anyMatch(r -> replacementMap.containsKey(r.id()));
				if (needsReplace) {
					List<ToolResponseMessage.ToolResponse> newResponses = new ArrayList<>();
					for (ToolResponseMessage.ToolResponse tr : trm.getResponses()) {
						String replacement = replacementMap.get(tr.id());
						if (replacement != null) {
							newResponses.add(new ToolResponseMessage.ToolResponse(tr.id(), tr.name(), replacement));
						} else {
							newResponses.add(tr);
						}
					}
					result.add(ToolResponseMessage.builder()
							.responses(newResponses)
							.metadata(trm.getMetadata())
							.build());
				} else {
					result.add(message);
				}
			} else {
				result.add(message);
			}
		}
		return result;
	}

	private String getPreview(String content) {
		if (content == null) return "";
		if (content.length() <= this.previewSize) {
			return content;
		}
		String truncated = content.substring(0, this.previewSize);
		int lastNewline = truncated.lastIndexOf('\n');
		int cutPoint = lastNewline > this.previewSize / 2 ? lastNewline : this.previewSize;
		return content.substring(0, cutPoint);
	}

	private String buildReplacementText(String redisKey, String originalData, String preview) {
		int originalSize = (originalData != null) ? originalData.length() : 0;
		boolean hasMore = preview.length() < originalSize;
		return PERSISTED_OUTPUT_TAG + "\n" +
				"Output too large (" + formatSize(originalSize) + "). Full output saved to Redis with key: " + redisKey + "\n\n" +
				"Preview (first " + formatSize(preview.length()) + "):\n" +
				preview +
				(hasMore ? "\n...\n" : "\n") +
				"</persisted-output>\n" +
				"(Use 'toolResultBudgetTool' with redisKey '" + redisKey + "' to read the full output)";
	}

	private String formatSize(int chars) {
		if (chars < 1024) return chars + " chars";
		if (chars < 1024 * 1024) return String.format("%.1f KB", chars / 1024.0);
		return String.format("%.1f MB", chars / (1024.0 * 1024.0));
	}

	private String extractAssistantId(Message message) {
		if (message instanceof AssistantMessage am) {
			Object id = am.getMetadata().get("id");
			// 如果没有找到 metadata 中的 id，则视为一条独立 assistantMessage，使用UUID进行赋值
			return id != null ? id.toString() : UUID.randomUUID().toString();
		}
		return null;
	}

	@Override
	public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
		return response;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	private record ToolGroup(List<ToolResponseMessage.ToolResponse> candidates) {
	}

	public static Builder builder(StringRedisTemplate redisTemplate) {
		return new Builder(redisTemplate);
	}

	public static final class Builder {
		// After the default DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER
		private int order = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 110;

		private final StringRedisTemplate redisTemplate;

		private final Set<String> skipToolNames = new HashSet<>();

		private int maxSingleResultChars = DEFAULT_MAX_SINGLE_RESULT_CHARS;

		private int maxPerGroupBudgetChars = DEFAULT_MAX_PER_GROUP_BUDGET_CHARS;

		private int previewSize = DEFAULT_PREVIEW_SIZE_CHARS;

		private Duration ttl = DEFAULT_TTL;

		private ChatMemory chatMemory;

		public Builder(StringRedisTemplate redisTemplate) {
			this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
		}

		public Builder order(int order) {
			this.order = order;
			return this;
		}

		public Builder maxSingleResultChars(int maxSingleResultChars) {
			this.maxSingleResultChars = maxSingleResultChars;
			return this;
		}

		public Builder maxPerGroupBudgetChars(int maxPerGroupBudgetChars) {
			this.maxPerGroupBudgetChars = maxPerGroupBudgetChars;
			return this;
		}

		public Builder ttl(Duration ttl) {
			this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
			return this;
		}

		public Builder previewSize(int previewSize) {
			this.previewSize = previewSize;
			return this;
		}

		public Builder skipToolName(String toolName) {
			this.skipToolNames.add(toolName);
			return this;
		}

		/**
		 * 批量添加不参与 budget 压缩的工具名称。
		 */
		public Builder skipToolNames(Collection<String> toolNames) {
			this.skipToolNames.addAll(toolNames);
			return this;
		}

		/**
		 * 如果设置了 chatMemory 实例，则将在发生压缩操作后将压缩后结果更新到 chatMemory 中
		 */
		public Builder chatMemory(ChatMemory chatMemory) {
			this.chatMemory = chatMemory;
			return this;
		}

		public ToolResultBudgetAdvisor build() {
			this.skipToolNames.add(ToolResultBudgetTool.TOOL_NAME);
			return new ToolResultBudgetAdvisor(this.redisTemplate, this.maxSingleResultChars,
					this.maxPerGroupBudgetChars, this.ttl, this.skipToolNames, this.previewSize, this.order, this.chatMemory);
		}
	}
}
