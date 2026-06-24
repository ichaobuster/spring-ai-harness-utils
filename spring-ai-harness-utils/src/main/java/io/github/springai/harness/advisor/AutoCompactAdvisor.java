package io.github.springai.harness.advisor;

import io.github.springai.harness.util.ChatMemoryUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Spring AI Advisor 实现，移植自 Claude Code 泄漏源码中 src/services/compact/autoCompact.ts 的 autoCompact 逻辑。
 * <p>
 * <b>核心功能</b>：在发送给 LLM 之前，检测消息列表的估算 token 数是否超过阈值。
 * 如果超过，调用一个独立的 ChatModel 将全部历史消息压缩为一条结构化摘要，
 * 用摘要替换原始消息后再继续发送主请求。
 * <p>
 * <b>工作流程</b>
 * <ol>
 *   <li><b>估算 token 数</b>：使用粗略字符估算（chars / 4）来近似消息 token 数。</li>
 *   <li><b>阈值判断</b>：当估算 token 数 ≥ {@code effectiveContextWindow - autocompactBufferTokens} 时触发。</li>
 *   <li><b>Circuit Breaker</b>：连续压缩失败超过 {@code maxConsecutiveFailures} 次后，停止尝试。</li>
 *   <li><b>LLM 压缩</b>：将全部消息历史 + 压缩提示词发送给 compactModel，获取结构化摘要。</li>
 *   <li><b>消息替换</b>：将原始消息列表替换为 [SystemMessage(boundary marker), UserMessage(summary)]。</li>
 * </ol>
 * <p>
 * <b>跨请求状态</b>：本 Advisor 通过 {@code advisorContext} 在 ChatClient 上下文中传递
 * {@link AutoCompactTrackingState}，以跨请求跟踪压缩状态（是否已压缩、连续失败次数等）。
 * 如果不在 advisorContext 中设置，则每次请求独立判断，不做跨请求 circuit breaking。
 * <p>
 * <b>与其他 Advisor 的关系</b>：
 * <ul>
 *   <li>AutoCompact 应在 {@link MicroCompactAdvisor} 之后运行（order 更大）</li>
 *   <li>AutoCompact 应在 {@link ToolResultBudgetAdvisor} 之后运行（order 更大）</li>
 * </ul>
 */
@Slf4j
public class AutoCompactAdvisor implements BaseChatMemoryAdvisor {

	public static final String DEFAULT_BASE_COMPACT_PROMPT = """
			CRITICAL: Respond with TEXT ONLY. Do NOT call any tools.
			                
			Your task is to create a detailed summary of the conversation so far, paying close attention to the user's explicit requests and your previous actions.
			                
			Before providing your final summary, wrap your analysis in <analysis> tags to organize your thoughts and ensure you've covered all necessary points. In your analysis process:
			                
			1. Chronologically analyze each message and section of the conversation. For each section thoroughly identify:
			   - The user's explicit requests and intents
			   - Your approach to addressing the user's requests
			   - Key decisions
			   - Specific details like UUIDs, hashes, IDs, tokens, API keys, hostnames, IPs, ports, URLs, and file names
			   - Errors that you ran into and how you fixed them
			   - Pay special attention to specific user feedback that you received, especially if the user told you to do something differently.
			2. Double-check for technical accuracy and completeness, addressing each required element thoroughly.
			                
			Your summary should include the following sections:
			                
			1. Primary Request and Intent: Capture all of the user's explicit requests and intents in detail
			2. Errors and fixes: List all errors that you ran into, and how you fixed them. Pay special attention to specific user feedback that you received, especially if the user told you to do something differently.
			3. Problem Solving: Document problems solved and any ongoing troubleshooting efforts.
			4. All user messages: List ALL user messages that are not tool results. These are critical for understanding the users' feedback and changing intent.
			5. Pending Tasks: Outline any pending tasks that you have explicitly been asked to work on.
			6. Current Work: Describe in detail precisely what was being worked on immediately before this summary request, paying special attention to the most recent messages from both user and assistant.
			7. Optional Next Step: List the next step that you will take that is related to the most recent work you were doing. IMPORTANT: ensure that this step is DIRECTLY in line with the user's most recent explicit requests, and the task you were working on immediately before this summary request.
			                
			DIRECTLY in line with the user's most recent explicit requests, and the task you were working on immediately before this summary request. If your last task was concluded, then only list next steps if they are explicitly in line with the users request. Do not start on tangential requests or really old requests that were already completed without confirming with the user first. \s
			If there is a next step, include direct quotes from the most recent conversation showing exactly what task you were working on and where you left off. This should be verbatim to ensure there's no drift in task interpretation.
						
			Here's an example of how your output should be structured: \s
			```
			<analysis>
			[Your thought process, ensuring all points are covered thoroughly and accurately]
			</analysis>
			<summary>
			1. Primary Request and Intent:
			   [Detailed description]
			   
			2. Errors and fixes:
			    - [Detailed description of error 1]:
			      - [How you fixed the error]
			      - [User feedback on the error if any]
			    - [...]
			   
			3. Problem Solving:
			   [Description of solved problems and ongoing troubleshooting]
			   
			4. All user messages:
			    - [Detailed non tool use user message]
			    - [...]
			   
			5. Pending Tasks:
			   - [Task 1]
			   - [Task 2]
			   - [...]
			   
			6. Current Work:
			   [Precise description of current work]
			   
			7. Optional Next Step:
			   [Optional Next step to take]
			   
			</summary>
			```
						
			Please provide your summary based on the conversation so far, following this structure and ensuring precision and thoroughness in your response.
						
			REMINDER: Do NOT call any tools. Respond with plain text only — an <analysis> block followed by a <summary> block. Tool calls will be rejected and you will fail the task.
			""";


	/**
	 * advisorContext 中的 tracking state key
	 */
	public static final String TRACKING_STATE_KEY = "autocompact_tracking_state";


	// ==================== TS 常量对齐 ====================

	/**
	 * 默认大模型 context window （上下文窗口大小）： 200k
	 */
	private static final int DEFAULT_CONTEXT_WINDOW = 200_000;

	/**
	 * 默认大模型输出 token 数: 20k
	 */
	private static final int DEFAULT_MAX_OUTPUT_TOKENS = 20_000;

	/**
	 * 默认自动压缩的 buffer 大小（tokens），即用于进行压缩总结的模型的最大输出 token 数（预算）: 20k
	 */
	private static final int DEFAULT_AUTOCOMPACT_BUFFER_TOKENS = 20_000;


	/**
	 * 默认最大连续失败次数: 对应 TS 的 MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES = 3
	 */
	private static final int DEFAULT_MAX_CONSECUTIVE_FAILURES = 3;

	/**
	 * 用于粗略估算 token 数的变量。
	 */
	private static final int BYTE_PER_TOKEN = 4;

	// ==================== 配置字段 ====================


	/**
	 * 用于执行压缩摘要的 ChatModel（可与主 model 不同）
	 */
	private final ChatModel compactModel;

	/**
	 * 模型上下文窗口大小（tokens）
	 */
	private final int contextWindow;

	/**
	 * 自动压缩的 buffer 大小（tokens），即用于进行压缩总结的模型的最大输出 token 数（预算）
	 */
	private final int autoCompactBufferTokens;

	/**
	 * 最大连续失败次数，超过后不再尝试压缩
	 */
	private final int maxConsecutiveFailures;

	/**
	 * LLM 最大输出 token 数，包括作为摘要最大输出 token 数
	 */
	private final int maxOutputTokens;

	/**
	 * 压缩用的prompt
	 */
	private final String baseCompactPrompt;

	/**
	 * 可选的自定义指令，附加到提示词末尾
	 */
	private final String customInstructions;

	private final int order;

	private final ChatMemory chatMemory;

	// ==================== 构造器 ====================

	/**
	 * 全参数构造器。
	 */
	public AutoCompactAdvisor(ChatModel compactModel,
							  int contextWindow,
							  int maxOutputTokens,
							  int autoCompactBufferTokens,
							  int maxConsecutiveFailures,
							  String baseCompactPrompt,
							  String customInstructions,
							  int order,
							  ChatMemory chatMemory) {
		this.compactModel = compactModel;
		this.contextWindow = contextWindow;
		this.maxOutputTokens = maxOutputTokens;
		this.autoCompactBufferTokens = autoCompactBufferTokens;
		this.maxConsecutiveFailures = maxConsecutiveFailures;
		this.baseCompactPrompt = baseCompactPrompt;
		this.customInstructions = customInstructions;
		this.order = order;
		this.chatMemory = chatMemory;
	}

	// ==================== BaseAdvisor 实现 ====================

	@Override
	public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
		if (request.prompt() == null || request.prompt().getInstructions() == null) {
			return request;
		}

		List<Message> originalMessages = request.prompt().getInstructions();
		// 拆分出 system messages 和 其他
		List<Message> systemMessages = originalMessages.stream().filter(message -> MessageType.SYSTEM == message.getMessageType()).collect(Collectors.toList());
		List<Message> messages = originalMessages.stream().filter(message -> MessageType.SYSTEM != message.getMessageType()).collect(Collectors.toList());
		if (messages.isEmpty()) {
			return request;
		}

		// --- 第一步：读取 tracking state（如果有） ---
		AutoCompactTrackingState tracking = getTrackingState(request.context());

		// --- 第二步：circuit breaker 检查 ---
		// 通过 tracking.getConsecutiveFailures() < 0 检查可能次数溢出的潜在威胁
		if (tracking.getConsecutiveFailures() < 0 || tracking.getConsecutiveFailures() >= maxConsecutiveFailures) {
			log.warn("Circuit breaker tripped: {} consecutive failures >= max {}",
					tracking.getConsecutiveFailures(), maxConsecutiveFailures);
			return request;
		}

		// --- 第三步：估算 token 数并判断是否超过阈值 ---
		int estimatedTokens = estimateTokenCount(originalMessages);
		int effectiveContextWindow = this.contextWindow - this.maxOutputTokens;
		int threshold = effectiveContextWindow - this.autoCompactBufferTokens;

		log.info("Token estimate: {} / threshold: {} (window={}, buffer={})",
				estimatedTokens, threshold, effectiveContextWindow, this.autoCompactBufferTokens);

		if (estimatedTokens < threshold) {
			return request;
		}

		// --- 第四步：执行压缩 ---
		log.info("AutoCompact triggered: estimatedTokens={} >= threshold={}",
				estimatedTokens, threshold);

		try {
			List<Message> compactedMessages = performCompaction(messages);
			List<Message> resultMessages = new ArrayList<>(systemMessages);
			resultMessages.addAll(compactedMessages);

			// 更新 tracking state: 成功
			tracking.setCompacted(true);

			ChatClientRequest result = request.mutate()
					.prompt(new Prompt(resultMessages, request.prompt().getOptions()))
					.build();

			int postCompactTokens = estimateTokenCount(compactedMessages);
			log.info("Compaction succeeded: {} tokens -> {} tokens ({} messages -> {} messages)",
					estimatedTokens, postCompactTokens, messages.size(), compactedMessages.size());

			ChatMemoryUtil.replaceChatMemoryMessages(this.chatMemory, request.context(), resultMessages);

			return result;

		} catch (Exception e) {
			log.warn("Compaction failed: {}", e.getMessage(), e);

			// 更新 tracking state: 失败计数+1
			int nextFailures = tracking.getConsecutiveFailures() + 1;
			if (nextFailures >= maxConsecutiveFailures) {
				log.warn("Circuit breaker will trip after {} consecutive failures",
						nextFailures);
			}
			tracking.setConsecutiveFailures(nextFailures);

			return request;
		}
	}

	@Override
	public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
		// 如果成功完成一轮（response 不为空），增加 turnCounter
		AutoCompactTrackingState tracking = getTrackingState(response.context());
		if (tracking.compacted) {
			tracking.incrementTurnCounter();
		}
		return response;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	// ==================== 核心逻辑 ====================

	/**
	 * 执行压缩：将移除 system messages 后的全部消息发送给 compactModel 并生成结构化摘要。
	 * <p>
	 * 对应 TS 的 compactConversation 流程：
	 * <ol>
	 *   <li>构建压缩提示词（getCompactPrompt）</li>
	 *   <li>将历史消息 + 提示词发送给 compact model</li>
	 *   <li>解析 summary（formatCompactSummary: 剥离 analysis，提取 summary）</li>
	 *   <li>构建 post-compact 消息列表</li>
	 * </ol>
	 */
	private List<Message> performCompaction(List<Message> originalMessages) {
		// 构建压缩提示词
		String compactPrompt = buildCompactPrompt(this.customInstructions);

		// 构建发送给 compact model 的消息列表：
		// 原始的历史消息 + 一条 UserMessage（压缩指令）
		List<Message> compactMessages = new ArrayList<>(originalMessages);
		compactMessages.add(new UserMessage(compactPrompt));

		// 调用 compact model
		Prompt compactPromptObj = new Prompt(compactMessages);

		ChatResponse compactResponse = compactModel.call(compactPromptObj);

		String rawSummary = compactResponse.getResult().getOutput().getText();

		if (rawSummary == null || rawSummary.isBlank()) {
			throw new RuntimeException("Compact model returned empty summary");
		}

		// 格式化摘要：剥离 <analysis>，提取 <summary>
		String formattedSummary = formatCompactSummary(rawSummary);

		// 构建 post-compact 消息列表
		return buildPostCompactMessages(formattedSummary, originalMessages);
	}

	/**
	 * 构建压缩提示词。
	 * <p>
	 * 对应 TS 的 {@code getCompactPrompt(customInstructions)}。
	 * 精简版：保留核心摘要指令，不包含 NO_TOOLS_PREAMBLE
	 * （因为 Spring AI 的 ChatModel.call 不走 tool 执行路径）。
	 *
	 * @param customInstructions 可选的自定义指令，附加到提示词末尾
	 */
	private String buildCompactPrompt(String customInstructions) {
		StringBuilder sb = new StringBuilder();

		sb.append(this.baseCompactPrompt);

		if (customInstructions != null && !customInstructions.isBlank()) {
			sb.append("\n\nAdditional Instructions:\n").append(customInstructions);
		}

		return sb.toString();
	}

	/**
	 * 格式化压缩摘要：剥离 {@code <analysis>} 标签内容，提取 {@code <summary>} 标签内容。
	 * <p>
	 * 对应 TS 的 {@code formatCompactSummary}。
	 */
	static String formatCompactSummary(String rawSummary) {
		String result = rawSummary;

		// 剥离 <analysis>...</analysis>
		result = result.replaceAll("(?s)<analysis>.*?</analysis>", "");

		// 提取 <summary>...</summary> 的内容
		Pattern summaryPattern = Pattern.compile("(?s)<summary>(.*?)</summary>");
		Matcher matcher = summaryPattern.matcher(result);
		if (matcher.find()) {
			String content = matcher.group(1);
			result = result.replaceAll("(?s)<summary>.*?</summary>",
					"Summary:\n" + Matcher.quoteReplacement(content.trim()));
		}

		// 清理多余空行
		result = result.replaceAll("\n{3,}", "\n\n");

		return result.trim();
	}

	/**
	 * 构建 post-compact 消息列表。
	 * <p>
	 * 对应 TS 的 {@code buildPostCompactMessages}：
	 * <pre>
	 * [boundaryMarker, ...summaryMessages, ...attachments, ...hookResults]
	 * </pre>
	 * <p>
	 * 在 Spring AI 简化版中：
	 * <pre>
	 * [SystemMessage(compact boundary), UserMessage(summary + continuation instruction)]
	 * </pre>
	 *
	 * @param formattedSummary 格式化后的摘要文本
	 * @param originalMessages 压缩前的消息
	 * @return 替换后的消息列表
	 */
	private List<Message> buildPostCompactMessages(String formattedSummary, List<Message> originalMessages) {
		List<Message> result = new ArrayList<>();
		int originalMessageCount = originalMessages.size();

		// 1. Compact boundary marker (SystemMessage)
		// 对应 TS 的 createCompactBoundaryMessage
		String boundaryText = String.format(
				"[Auto-compact: %d messages compressed into summary]", originalMessageCount);
		result.add(new SystemMessage(boundaryText));

		// 2. Summary user message
		// 对应 TS 的 getCompactUserSummaryMessage + createUserMessage
		String summaryContent = buildSummaryUserMessage(formattedSummary);
		result.add(new UserMessage(summaryContent));

		return result;
	}

	/**
	 * 构建摘要 UserMessage 的内容。
	 * <p>
	 * 对应 TS 的 {@code getCompactUserSummaryMessage}。
	 * 包含：
	 * <ul>
	 *   <li>上下文说明（这是从更早的对话压缩而来）</li>
	 *   <li>格式化的摘要</li>
	 *   <li>continuation 指令（继续工作而不要重复总结）</li>
	 * </ul>
	 */
	private String buildSummaryUserMessage(String formattedSummary) {
		return """
				This session is being continued from a previous conversation that ran out of context. The summary below covers the earlier portion of the conversation.
				                
				%s
				                
				Continue the conversation from where it left off without asking the user any further questions. Resume directly — do not acknowledge the summary, do not recap what was happening, do not preface with "I'll continue" or similar. Pick up the last task as if the break never happened.
				""".formatted(formattedSummary);
	}

	// ==================== Token 估算 ====================

	/**
	 * 估算消息列表的总 token 数。
	 * <p>
	 * 对应 TS 的 {@code tokenCountWithEstimation}（简化版）：
	 * <ul>
	 *   <li>TS 完整版先尝试从最后一条 Assistant 消息的 usage 中读取真实 token 数，
	 *       然后加上新增消息的粗略估计。</li>
	 *   <li>Spring AI 简化版：由于 Spring AI 的 Message 不携带 API usage 元数据，
	 *       我们直接对所有消息使用粗略字符估算。</li>
	 * </ul>
	 * <p>
	 * 粗略估算公式：字符数 / 4（对应 TS 的 {@code roughTokenCountEstimation}）。
	 */
	private int estimateTokenCount(List<Message> messages) {
		int totalChars = 0;
		for (Message message : messages) {
			totalChars += estimateMessageChars(message);
		}
		// 粗略 token 估算：chars / 4
		return totalChars / BYTE_PER_TOKEN;
	}

	/**
	 * 估算单条消息的字符数。
	 */
	private int estimateMessageChars(Message message) {
		if (message instanceof UserMessage um) {
			return um.getText() != null ? um.getText().length() : 0;
		} else if (message instanceof AssistantMessage am) {
			int chars = am.getText() != null ? am.getText().length() : 0;
			// 加上 tool calls 的 JSON 内容估算
			if (am.getToolCalls() != null) {
				for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
					chars += tc.name() != null ? tc.name().length() : 0;
					chars += tc.arguments() != null ? tc.arguments().length() : 0;
				}
			}
			return chars;
		} else if (message instanceof ToolResponseMessage trm) {
			int chars = 0;
			for (ToolResponseMessage.ToolResponse tr : trm.getResponses()) {
				chars += tr.responseData() != null ? tr.responseData().length() : 0;
			}
			return chars;
		} else if (message instanceof SystemMessage sm) {
			return sm.getText() != null ? sm.getText().length() : 0;
		}
		return 0;
	}

	// ==================== Tracking State ====================

	/**
	 * 从 ChatClientRequest 或 ChatClientResponse 的 context 中获取 tracking state。
	 */
	private AutoCompactTrackingState getTrackingState(Map<String, Object> context) {
		Object obj = context.get(TRACKING_STATE_KEY);
		return obj instanceof AutoCompactTrackingState state ? state : new AutoCompactTrackingState(false, maxConsecutiveFailures - 1, 0);
	}

	// ==================== Builder ====================

	public static Builder builder(ChatModel compactModel) {
		return new Builder(compactModel);
	}

	public Consumer<ChatClient.AdvisorSpec> advisorSpecConsumer() {
		return advisorSpec -> {
			advisorSpec.advisors(this);
			advisorSpec.param(TRACKING_STATE_KEY, new AutoCompactTrackingState());
		};
	}

	public static class Builder {
		private final ChatModel compactModel;
		private int contextWindow = DEFAULT_CONTEXT_WINDOW;
		private int autoCompactBufferTokens = DEFAULT_AUTOCOMPACT_BUFFER_TOKENS;

		private int maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS;
		private int maxConsecutiveFailures = DEFAULT_MAX_CONSECUTIVE_FAILURES;

		// After the default DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER
		private int order = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 150;

		private String baseCompactPrompt = DEFAULT_BASE_COMPACT_PROMPT;
		private String customInstructions;

		private ChatMemory chatMemory;

		public Builder(ChatModel compactModel) {
			this.compactModel = Objects.requireNonNull(compactModel, "compactModel must not be null");
		}

		/**
		 * LLM 上下文窗口大小（tokens），这里指的是被 ChatClient 调用的模型。
		 * 默认 200_000（200k）。
		 */
		public Builder contextWindow(int tokens) {
			this.contextWindow = tokens;
			return this;
		}

		/**
		 * 自动压缩的 buffer 大小（tokens），即用于进行压缩总结的模型的最大输出 token 数（预算）
		 * 默认 20_000。
		 * 这里指的是用于压缩的模型 compactModel，可能不同于被 ChatClient 调用的模型！
		 */
		public Builder autoCompactBufferTokens(int tokens) {
			this.autoCompactBufferTokens = tokens;
			return this;
		}

		/**
		 * 最大连续失败次数。默认 3。
		 */
		public Builder maxConsecutiveFailures(int count) {
			this.maxConsecutiveFailures = count;
			return this;
		}

		/**
		 * LLM 最大输出 token 数，用于计算是否达到了需要 autocompact 压缩的阈值。
		 * 这里指的是被 ChatClient 调用的模型。
		 */
		public Builder maxOutputTokens(int tokens) {
			this.maxOutputTokens = tokens;
			return this;
		}

		/**
		 * 设置压缩用的prompt模板
		 *
		 * @param prompt
		 * @return Builder实例
		 */
		public Builder baseCompactPrompt(String prompt) {
			this.baseCompactPrompt = prompt;
			return this;
		}

		/**
		 * 设置自定义指令，追加到压缩提示词最后，例如：请使用简体中文
		 *
		 * @param customInstructions
		 * @return Builder实例
		 */
		public Builder customInstructions(String customInstructions) {
			this.customInstructions = customInstructions;
			return this;
		}

		/**
		 * Advisor 执行顺序。默认 0。
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

		public AutoCompactAdvisor build() {
			return new AutoCompactAdvisor(
					compactModel,
					contextWindow,
					maxOutputTokens,
					autoCompactBufferTokens,
					maxConsecutiveFailures,
					baseCompactPrompt,
					customInstructions,
					order,
					chatMemory
			);
		}
	}

	// ==================== 内部数据结构 ====================

	/**
	 * 跨请求的 autoCompact 追踪状态。
	 * <p>
	 * 对应 TS 的 {@code AutoCompactTrackingState}：
	 * <pre>
	 * type AutoCompactTrackingState = {
	 *   compacted: boolean
	 *   turnCounter: number
	 *   turnId: string
	 *   consecutiveFailures?: number
	 * }
	 * </pre>
	 */
	@Data
	public static class AutoCompactTrackingState {
		/**
		 * 是否已经执行过压缩
		 */
		private boolean compacted;
		/**
		 * 连续失败次数
		 */
		private int consecutiveFailures;
		/**
		 * 自上次压缩以来的轮次计数
		 */
		private int turnCounter;

		public AutoCompactTrackingState() {
			this.compacted = false;
			this.consecutiveFailures = 0;
			this.turnCounter = 0;
		}

		public AutoCompactTrackingState(boolean compacted, int consecutiveFailures, int turnCounter) {
			this.compacted = compacted;
			this.consecutiveFailures = consecutiveFailures;
			this.turnCounter = turnCounter;
		}

		public void incrementTurnCounter() {
			this.turnCounter++;
		}

	}
}
