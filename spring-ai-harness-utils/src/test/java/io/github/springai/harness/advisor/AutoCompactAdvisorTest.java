package io.github.springai.harness.advisor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.DefaultChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AutocompactAdvisor 的单元测试。
 * <p>
 * 目标覆盖率：行覆盖 ≥ 90%，分支覆盖 ≥ 80%。
 * 所有外部依赖（ChatModel / LLM 调用）均使用 Mockito mock。
 */
@ExtendWith(MockitoExtension.class)
class AutoCompactAdvisorTest {

	@Mock
	private ChatModel mockCompactModel;

	/**
	 * 默认使用极小的 context window，方便触发 autoCompact。
	 * contextWindow=100, maxOutput=5, buffer=5 → threshold=90 tokens → 360 chars 即触发
	 */
	private AutoCompactAdvisor advisor;

	@BeforeEach
	void setUp() {
		advisor = AutoCompactAdvisor.builder(mockCompactModel)
				.contextWindow(100)
				.autoCompactBufferTokens(5)
				.maxOutputTokens(5)
				.maxConsecutiveFailures(3)
				.order(300)
				.chatMemory(mock(ChatMemory.class))
				.build();
	}

	// ==================== Helper 方法 ====================

	/**
	 * 创建一个包含指定消息列表的 ChatClientRequest。
	 */
	private ChatClientRequest createRequest(List<Message> messages) {
		return ChatClientRequest.builder()
				.prompt(new Prompt(messages, ChatOptions.builder().build()))
				.build();
	}

	/**
	 * 创建一个包含指定消息列表和 context 的 ChatClientRequest。
	 */
	private ChatClientRequest createRequestWithContext(List<Message> messages,
													   String key, Object value) {
		return ChatClientRequest.builder()
				.prompt(new Prompt(messages, ChatOptions.builder().build()))
				.context(key, value)
				.build();
	}

	private ChatClientRequest createRequestWithContextCreator(List<Message> messages) {
		return createRequestWithContext(messages, AutoCompactAdvisor.TRACKING_STATE_KEY, new AutoCompactAdvisor.AutoCompactTrackingState(false, 0, 0));
	}

	/**
	 * 创建指定长度的字符串，用于控制 token 估算。
	 * chars / 4 = estimated tokens
	 */
	private String stringOfLength(int length) {
		return "x".repeat(length);
	}

	/**
	 * 配置 mock ChatModel 返回指定的摘要文本。
	 */
	private void mockCompactModelReturns(String summaryText) {
		AssistantMessage assistantMsg = new AssistantMessage(summaryText);
		Generation generation = new Generation(assistantMsg);
		ChatResponse chatResponse = new ChatResponse(List.of(generation));
		when(mockCompactModel.call(any(Prompt.class))).thenReturn(chatResponse);
	}

	/**
	 * 配置 mock ChatModel 抛出异常。
	 */
	private void mockCompactModelThrows(RuntimeException exception) {
		when(mockCompactModel.call(any(Prompt.class))).thenThrow(exception);
	}

	/**
	 * 创建一个 ChatClientResponse，可选带有 tracking state。
	 */
	private ChatClientResponse createResponse(AutoCompactAdvisor.AutoCompactTrackingState tracking) {
		ChatClientResponse.Builder builder = ChatClientResponse.builder()
				.chatResponse(new ChatResponse(List.of(
						new Generation(new AssistantMessage("test response")))));
		if (tracking != null) {
			builder.context(AutoCompactAdvisor.TRACKING_STATE_KEY, tracking);
		}
		return builder.build();
	}

	// ==================== before() 方法测试 ====================

	@Nested
	@DisplayName("before() — 短路返回路径")
	class BeforeShortCircuit {

		@Test
		@DisplayName("prompt 为 null 时直接返回")
		void whenPromptNull_returnsOriginalRequest() {
			// 构建一个 prompt 为 null 的 request — 通过空消息列表触发 null instructions 路径
			// 实际上 Spring AI 不太允许 null prompt，我们测试 empty messages
			ChatClientRequest request = createRequest(Collections.emptyList());
			ChatClientRequest result = advisor.before(request, null);

			assertThat(result).isSameAs(request);
			verifyNoInteractions(mockCompactModel);
		}

		@Test
		@DisplayName("消息列表为空时直接返回")
		void whenEmptyMessages_returnsOriginalRequest() {
			ChatClientRequest request = createRequest(new ArrayList<>());
			ChatClientRequest result = advisor.before(request, null);

			assertThat(result).isSameAs(request);
			verifyNoInteractions(mockCompactModel);
		}
	}

	@Nested
	@DisplayName("before() — Circuit Breaker")
	class BeforeCircuitBreaker {

		@Test
		@DisplayName("连续失败达到上限时 circuit breaker 触发，不再尝试压缩")
		void whenConsecutiveFailuresAtMax_circuitBreakerTrips() {
			// 400 chars → 100 tokens > threshold(90)，但 circuit breaker 应阻止
			List<Message> messages = List.of(new UserMessage(stringOfLength(400)));
			AutoCompactAdvisor.AutoCompactTrackingState tracking =
					new AutoCompactAdvisor.AutoCompactTrackingState(false, 3, 0);
			ChatClientRequest request = createRequestWithContext(messages,
					AutoCompactAdvisor.TRACKING_STATE_KEY, tracking);

			ChatClientRequest result = advisor.before(request, null);

			assertThat(result).isSameAs(request);
			verifyNoInteractions(mockCompactModel);
		}

		@Test
		@DisplayName("连续失败超过上限时也触发 circuit breaker")
		void whenConsecutiveFailuresAboveMax_circuitBreakerTrips() {
			List<Message> messages = List.of(new UserMessage(stringOfLength(400)));
			AutoCompactAdvisor.AutoCompactTrackingState tracking =
					new AutoCompactAdvisor.AutoCompactTrackingState(true, 5, 2);
			ChatClientRequest request = createRequestWithContext(messages,
					AutoCompactAdvisor.TRACKING_STATE_KEY, tracking);

			ChatClientRequest result = advisor.before(request, null);

			assertThat(result).isSameAs(request);
			verifyNoInteractions(mockCompactModel);
		}

		@Test
		@DisplayName("连续失败未达上限时继续尝试压缩")
		void whenConsecutiveFailuresBelowMax_proceedsNormally() {
			// 400 chars → 100 tokens ≥ threshold(90)
			List<Message> messages = List.of(new UserMessage(stringOfLength(400)));
			AutoCompactAdvisor.AutoCompactTrackingState tracking =
					new AutoCompactAdvisor.AutoCompactTrackingState(false, 2, 0);
			ChatClientRequest request = createRequestWithContext(messages,
					AutoCompactAdvisor.TRACKING_STATE_KEY, tracking);

			mockCompactModelReturns("<analysis>thinking</analysis><summary>Summary content</summary>");

			ChatClientRequest result = advisor.before(request, null);

			assertThat(result).isNotSameAs(request);
			verify(mockCompactModel).call(any(Prompt.class));
		}
	}

	@Nested
	@DisplayName("before() — 阈值判断")
	class BeforeThreshold {

		@Test
		@DisplayName("token 数低于阈值时不触发压缩")
		void whenBelowThreshold_noCompaction() {
			// 100 chars → 25 tokens < threshold(90)
			List<Message> messages = List.of(new UserMessage(stringOfLength(100)));
			ChatClientRequest request = createRequestWithContextCreator(messages);

			ChatClientRequest result = advisor.before(request, null);

			assertThat(result).isSameAs(request);
			verifyNoInteractions(mockCompactModel);
		}

		@Test
		@DisplayName("token 数恰好等于阈值时触发压缩")
		void whenExactlyAtThreshold_triggersCompaction() {
			// threshold = 100 - 10 = 90 tokens → 360 chars
			List<Message> messages = List.of(new UserMessage(stringOfLength(360)));
			ChatClientRequest request = createRequestWithContextCreator(messages);

			mockCompactModelReturns("<summary>Compact summary</summary>");

			ChatClientRequest result = advisor.before(request, null);

			assertThat(result).isNotSameAs(request);
			verify(mockCompactModel).call(any(Prompt.class));
		}

		@Test
		@DisplayName("token 数超过阈值时触发压缩")
		void whenAboveThreshold_triggersCompaction() {
			// 500 chars → 125 tokens > threshold(90)
			List<Message> messages = List.of(new UserMessage(stringOfLength(500)));
			ChatClientRequest request = createRequestWithContextCreator(messages);

			mockCompactModelReturns("<summary>Compact summary</summary>");

			ChatClientRequest result = advisor.before(request, null);

			assertThat(result).isNotSameAs(request);
			verify(mockCompactModel).call(any(Prompt.class));
		}
	}

	@Nested
	@DisplayName("before() — 压缩成功路径")
	class BeforeCompactionSuccess {

		@Test
		@DisplayName("压缩成功时返回包含 SystemMessage + UserMessage 的新消息列表")
		void whenCompactionSucceeds_returnsCompactedMessages() {
			List<Message> messages = List.of(new UserMessage(stringOfLength(500)));
			ChatClientRequest request = createRequestWithContextCreator(messages);

			mockCompactModelReturns("<analysis>analysis text</analysis><summary>The summary</summary>");

			ChatClientRequest result = advisor.before(request, null);

			List<Message> newMessages = result.prompt().getInstructions();
			assertThat(newMessages).hasSize(2);
			assertThat(newMessages.get(0)).isInstanceOf(SystemMessage.class);
			assertThat(newMessages.get(0).getText()).contains("Auto-compact");
			assertThat(newMessages.get(0).getText()).contains("1 messages compressed");
			assertThat(newMessages.get(1)).isInstanceOf(UserMessage.class);
			assertThat(newMessages.get(1).getText()).contains("The summary");
			assertThat(newMessages.get(1).getText()).contains("continued from a previous conversation");
		}

		@Test
		@DisplayName("压缩成功后 tracking state 被正确设置")
		void whenCompactionSucceeds_trackingStateIsUpdated() {
			List<Message> messages = List.of(new UserMessage(stringOfLength(500)));
			ChatClientRequest request = createRequestWithContextCreator(messages);

			mockCompactModelReturns("<summary>Summary</summary>");

			ChatClientRequest result = advisor.before(request, null);

			Object trackingObj = result.context().get(AutoCompactAdvisor.TRACKING_STATE_KEY);
			assertThat(trackingObj).isInstanceOf(AutoCompactAdvisor.AutoCompactTrackingState.class);
			AutoCompactAdvisor.AutoCompactTrackingState state =
					(AutoCompactAdvisor.AutoCompactTrackingState) trackingObj;
			assertThat(state.isCompacted()).isTrue();
			assertThat(state.getConsecutiveFailures()).isEqualTo(0);
			assertThat(state.getTurnCounter()).isEqualTo(0);
		}

		@Test
		@DisplayName("压缩成功后 tracking state 被正确设置(默认缺少track时)")
		void whenCompactionSucceeds_defaultTrackingStateNull() {
			List<Message> messages = List.of(new UserMessage(stringOfLength(500)));
			ChatClientRequest request = createRequest(messages);

			mockCompactModelReturns("<summary>Summary</summary>");

			ChatClientRequest result = advisor.before(request, null);

			Object trackingObj = result.context().get(AutoCompactAdvisor.TRACKING_STATE_KEY);
			assertThat(trackingObj).isNull();
		}

		@Test
		@DisplayName("compact model 接收到正确的 Prompt 参数")
		void whenCompactionTriggered_compactModelReceivesCorrectPrompt() {
			List<Message> messages = List.of(
					new UserMessage("hello world"),
					new AssistantMessage("I can help you"));
			// 两条消息太短，需要更大的消息来触发
			List<Message> bigMessages = List.of(new UserMessage(stringOfLength(500)));
			ChatClientRequest request = createRequestWithContextCreator(bigMessages);

			mockCompactModelReturns("<summary>Summary</summary>");

			advisor.before(request, null);

			ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
			verify(mockCompactModel).call(promptCaptor.capture());

			Prompt capturedPrompt = promptCaptor.getValue();
			List<Message> promptMessages = capturedPrompt.getInstructions();
			// 应该是原始消息 + 压缩提示词（UserMessage）
			assertThat(promptMessages).hasSize(2);
			assertThat(promptMessages.get(0)).isInstanceOf(UserMessage.class);
			// 最后一条应该是压缩指令
			assertThat(promptMessages.get(1)).isInstanceOf(UserMessage.class);
			assertThat(promptMessages.get(1).getText()).contains("CRITICAL: Respond with TEXT ONLY");
			assertThat(promptMessages.get(1).getText()).contains("<summary>");
		}

		@Test
		@DisplayName("多条消息的压缩——boundaryText 反映原始消息数")
		void whenMultipleMessages_boundaryReflectsOriginalCount() {
			List<Message> messages = List.of(
					new UserMessage(stringOfLength(200)),
					new AssistantMessage(stringOfLength(200)),
					new UserMessage(stringOfLength(200)));
			ChatClientRequest request = createRequestWithContextCreator(messages);

			mockCompactModelReturns("<summary>Summary</summary>");

			ChatClientRequest result = advisor.before(request, null);

			List<Message> newMessages = result.prompt().getInstructions();
			assertThat(newMessages.get(0).getText()).contains("3 messages compressed");
		}
	}

	@Nested
	@DisplayName("before() — 压缩失败路径")
	class BeforeCompactionFailure {

		@Test
		@DisplayName("compact model 抛异常时，tracking state 记录失败次数（无已有 tracking）")
		void whenCompactionFails_withoutTracking_incrementsFailures() {
			List<Message> messages = List.of(new UserMessage(stringOfLength(500)));
			ChatClientRequest request = createRequestWithContextCreator(messages);

			mockCompactModelThrows(new RuntimeException("API error"));

			ChatClientRequest result = advisor.before(request, null);

			// 应返回原 request 的 mutate（带 tracking state）
			Object trackingObj = result.context().get(AutoCompactAdvisor.TRACKING_STATE_KEY);
			assertThat(trackingObj).isInstanceOf(AutoCompactAdvisor.AutoCompactTrackingState.class);
			AutoCompactAdvisor.AutoCompactTrackingState state =
					(AutoCompactAdvisor.AutoCompactTrackingState) trackingObj;
			assertThat(state.isCompacted()).isFalse();
			assertThat(state.getConsecutiveFailures()).isEqualTo(1);
			assertThat(state.getTurnCounter()).isEqualTo(0);
		}

		@Test
		@DisplayName("compact model 抛异常时，累计已有 tracking 的失败次数")
		void whenCompactionFails_withTracking_accumulatesFailures() {
			List<Message> messages = List.of(new UserMessage(stringOfLength(500)));
			AutoCompactAdvisor.AutoCompactTrackingState existingTracking =
					new AutoCompactAdvisor.AutoCompactTrackingState(true, 1, 5);
			ChatClientRequest request = createRequestWithContext(messages,
					AutoCompactAdvisor.TRACKING_STATE_KEY, existingTracking);

			mockCompactModelThrows(new RuntimeException("Network timeout"));

			ChatClientRequest result = advisor.before(request, null);

			AutoCompactAdvisor.AutoCompactTrackingState state =
					(AutoCompactAdvisor.AutoCompactTrackingState) result.context()
							.get(AutoCompactAdvisor.TRACKING_STATE_KEY);
			assertThat(state.isCompacted()).isTrue(); // 保留原来的 compacted 状态
			assertThat(state.getConsecutiveFailures()).isEqualTo(2);
			assertThat(state.getTurnCounter()).isEqualTo(5); // 保留原来的 turnCounter
		}

		@Test
		@DisplayName("失败次数达到上限时触发 circuit breaker 日志（但仍返回 request）")
		void whenCompactionFails_reachingMax_triggersCircuitBreakerLog() {
			List<Message> messages = List.of(new UserMessage(stringOfLength(500)));
			AutoCompactAdvisor.AutoCompactTrackingState existingTracking =
					new AutoCompactAdvisor.AutoCompactTrackingState(false, 2, 0);
			ChatClientRequest request = createRequestWithContext(messages,
					AutoCompactAdvisor.TRACKING_STATE_KEY, existingTracking);

			mockCompactModelThrows(new RuntimeException("Failure #3"));

			ChatClientRequest result = advisor.before(request, null);

			AutoCompactAdvisor.AutoCompactTrackingState state =
					(AutoCompactAdvisor.AutoCompactTrackingState) result.context()
							.get(AutoCompactAdvisor.TRACKING_STATE_KEY);
			// 第三次失败 → consecutiveFailures=3 >= maxConsecutiveFailures(3)
			assertThat(state.getConsecutiveFailures()).isEqualTo(3);
		}

		@Test
		@DisplayName("compact model 返回空摘要时抛出 RuntimeException")
		void whenCompactModelReturnsEmpty_throwsCaughtAndRecorded() {
			List<Message> messages = List.of(new UserMessage(stringOfLength(500)));
			ChatClientRequest request = createRequestWithContextCreator(messages);

			// 返回空白内容
			mockCompactModelReturns("   ");

			ChatClientRequest result = advisor.before(request, null);

			// 空摘要导致 performCompaction 内部抛 RuntimeException，被 catch 处理
			AutoCompactAdvisor.AutoCompactTrackingState state =
					(AutoCompactAdvisor.AutoCompactTrackingState) result.context()
							.get(AutoCompactAdvisor.TRACKING_STATE_KEY);
			assertThat(state.getConsecutiveFailures()).isEqualTo(1);
		}

		@Test
		@DisplayName("compact model 返回 null 文本时抛出异常并记录失败")
		void whenCompactModelReturnsNull_throwsCaughtAndRecorded() {
			List<Message> messages = List.of(new UserMessage(stringOfLength(500)));
			ChatClientRequest request = createRequestWithContextCreator(messages);

			// 返回 null 文本
			AssistantMessage assistantMsg = new AssistantMessage((String) null);
			Generation generation = new Generation(assistantMsg);
			ChatResponse chatResponse = new ChatResponse(List.of(generation));
			when(mockCompactModel.call(any(Prompt.class))).thenReturn(chatResponse);

			ChatClientRequest result = advisor.before(request, null);

			AutoCompactAdvisor.AutoCompactTrackingState state =
					(AutoCompactAdvisor.AutoCompactTrackingState) result.context()
							.get(AutoCompactAdvisor.TRACKING_STATE_KEY);
			assertThat(state.getConsecutiveFailures()).isEqualTo(1);
		}
	}

	// ==================== after() 方法测试 ====================

	@Nested
	@DisplayName("after() — 响应处理")
	class AfterResponse {

		@Test
		@DisplayName("无 tracking state 时直接返回原响应")
		void whenNoTrackingState_returnsOriginalResponse() {
			ChatClientResponse response = createResponse(null);
			ChatClientResponse result = advisor.after(response, null);

			assertThat(result).isSameAs(response);
		}

		@Test
		@DisplayName("tracking.compacted=false 时直接返回原响应")
		void whenNotCompacted_returnsOriginalResponse() {
			AutoCompactAdvisor.AutoCompactTrackingState tracking =
					new AutoCompactAdvisor.AutoCompactTrackingState(false, 0, 0);
			ChatClientResponse response = createResponse(tracking);

			ChatClientResponse result = advisor.after(response, null);

			assertThat(result).isSameAs(response);
		}

		@Test
		@DisplayName("tracking.compacted=true 时增加 turnCounter")
		void whenCompacted_incrementsTurnCounter() {
			AutoCompactAdvisor.AutoCompactTrackingState tracking =
					new AutoCompactAdvisor.AutoCompactTrackingState(true, 0, 5);
			ChatClientResponse response = createResponse(tracking);

			ChatClientResponse result = advisor.after(response, null);

			AutoCompactAdvisor.AutoCompactTrackingState updatedState =
					(AutoCompactAdvisor.AutoCompactTrackingState) result.context()
							.get(AutoCompactAdvisor.TRACKING_STATE_KEY);
			assertThat(updatedState).isNotNull();
			assertThat(updatedState.isCompacted()).isTrue();
			assertThat(updatedState.getConsecutiveFailures()).isEqualTo(0);
			assertThat(updatedState.getTurnCounter()).isEqualTo(6);
		}

		@Test
		@DisplayName("context 中有非 TrackingState 类型的值时返回原响应")
		void whenContextHasWrongType_returnsOriginalResponse() {
			ChatClientResponse response = ChatClientResponse.builder()
					.chatResponse(new ChatResponse(List.of(
							new Generation(new AssistantMessage("test")))))
					.context(AutoCompactAdvisor.TRACKING_STATE_KEY, "not a tracking state")
					.build();

			ChatClientResponse result = advisor.after(response, null);

			assertThat(result).isSameAs(response);
		}
	}

	// ==================== formatCompactSummary() 测试 ====================

	@Nested
	@DisplayName("formatCompactSummary() — 摘要格式化")
	class FormatCompactSummary {

		@Test
		@DisplayName("剥离 <analysis> 并提取 <summary> 内容")
		void stripsAnalysisAndExtractsSummary() {
			String raw = "<analysis>Some analysis here</analysis>\n\n<summary>The actual summary</summary>";
			String result = AutoCompactAdvisor.formatCompactSummary(raw);

			assertThat(result).doesNotContain("<analysis>");
			assertThat(result).doesNotContain("</analysis>");
			assertThat(result).doesNotContain("<summary>");
			assertThat(result).doesNotContain("</summary>");
			assertThat(result).contains("Summary:");
			assertThat(result).contains("The actual summary");
		}

		@Test
		@DisplayName("多行 <analysis> 内容被正确剥离")
		void stripsMultiLineAnalysis() {
			String raw = """
					<analysis>
					Line 1 of analysis
					Line 2 of analysis
					</analysis>
					<summary>Clean summary</summary>""";
			String result = AutoCompactAdvisor.formatCompactSummary(raw);

			assertThat(result).doesNotContain("Line 1 of analysis");
			assertThat(result).contains("Summary:");
			assertThat(result).contains("Clean summary");
		}

		@Test
		@DisplayName("没有 <summary> 标签时保留原始文本")
		void whenNoSummaryTags_keepsOriginalText() {
			String raw = "Just plain text summary without tags";
			String result = AutoCompactAdvisor.formatCompactSummary(raw);

			assertThat(result).isEqualTo("Just plain text summary without tags");
		}

		@Test
		@DisplayName("只有 <summary> 没有 <analysis> 时正确处理")
		void whenOnlySummaryNoAnalysis_extractsSummary() {
			String raw = "<summary>Summary only</summary>";
			String result = AutoCompactAdvisor.formatCompactSummary(raw);

			assertThat(result).contains("Summary:");
			assertThat(result).contains("Summary only");
		}

		@Test
		@DisplayName("清理多余空行")
		void cleansUpExtraBlankLines() {
			String raw = "Line 1\n\n\n\n\nLine 2";
			String result = AutoCompactAdvisor.formatCompactSummary(raw);

			assertThat(result).isEqualTo("Line 1\n\nLine 2");
		}

		@Test
		@DisplayName("<summary> 内容前后的空白被 trim")
		void trimsSummaryContent() {
			String raw = "<summary>  \n  Clean content  \n  </summary>";
			String result = AutoCompactAdvisor.formatCompactSummary(raw);

			assertThat(result).contains("Summary:\nClean content");
		}
	}

	// ==================== Token 估算测试 ====================

	@Nested
	@DisplayName("Token 估算 — 各消息类型")
	class TokenEstimation {

		@Test
		@DisplayName("UserMessage 的 token 估算")
		void userMessage_tokenEstimation() {
			// 400 chars → 100 tokens → 触发 (threshold=90)
			List<Message> messages = List.of(new UserMessage(stringOfLength(400)));
			ChatClientRequest request = createRequestWithContextCreator(messages);

			mockCompactModelReturns("<summary>Summary</summary>");
			ChatClientRequest result = advisor.before(request, null);

			// 应触发压缩
			assertThat(result).isNotSameAs(request);
			verify(mockCompactModel).call(any(Prompt.class));
		}

		@Test
		@DisplayName("AssistantMessage（含 toolCalls）的 token 估算")
		void assistantMessage_withToolCalls_tokenEstimation() {
			AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
					"call_1", "tool", "myTool", "{\"key\": \"value\"}");
			AssistantMessage am = AssistantMessage.builder()
					.content(stringOfLength(300))
					.toolCalls(List.of(toolCall))
					.build();

			// 300 chars (text) + 6 (name) + 16 (arguments) = 322 chars → 80 tokens < 90
			// 加上更多内容来触发
			List<Message> messages = List.of(
					am,
					new UserMessage(stringOfLength(100)));
			// total = 322 + 100 = 422 chars → 105 tokens ≥ 90

			ChatClientRequest request = createRequestWithContextCreator(messages);
			mockCompactModelReturns("<summary>Summary</summary>");

			ChatClientRequest result = advisor.before(request, null);

			assertThat(result).isNotSameAs(request);
		}

		@Test
		@DisplayName("AssistantMessage（无 toolCalls）的 token 估算")
		void assistantMessage_withoutToolCalls_tokenEstimation() {
			AssistantMessage am = new AssistantMessage(stringOfLength(100));
			List<Message> messages = List.of(am);
			// 100 chars → 25 tokens < 90

			ChatClientRequest request = createRequestWithContextCreator(messages);
			ChatClientRequest result = advisor.before(request, null);

			assertThat(result).isSameAs(request);
		}

		@Test
		@DisplayName("ToolResponseMessage 的 token 估算")
		void toolResponseMessage_tokenEstimation() {
			ToolResponseMessage trm = ToolResponseMessage.builder()
					.responses(List.of(new ToolResponseMessage.ToolResponse("id1", "tool1",
							stringOfLength(400))))
					.build();

			List<Message> messages = List.of(trm);
			// 400 chars → 100 tokens ≥ 90 → 触发

			ChatClientRequest request = createRequestWithContextCreator(messages);
			mockCompactModelReturns("<summary>Summary</summary>");

			ChatClientRequest result = advisor.before(request, null);

			assertThat(result).isNotSameAs(request);
		}

		@Test
		@DisplayName("ToolResponseMessage 中 null responseData 的处理")
		void toolResponseMessage_nullData_handledGracefully() {
			ToolResponseMessage trm = ToolResponseMessage.builder()
					.responses(List.of(new ToolResponseMessage.ToolResponse("id1", "tool1", null)))
					.build();

			List<Message> messages = List.of(trm);
			// null data → 0 chars → 0 tokens < 90

			ChatClientRequest request = createRequestWithContextCreator(messages);
			ChatClientRequest result = advisor.before(request, null);

			assertThat(result).isSameAs(request);
		}

		@Test
		@DisplayName("含 SystemMessage 的 token 估算")
		void includes_systemMessage_tokenEstimation() {
			SystemMessage sm = new SystemMessage(stringOfLength(300));
			UserMessage um = new UserMessage(stringOfLength(100));
			List<Message> messages = List.of(sm, um);
			// 300 + 100 chars = 400 chars → 100 tokens ≥ 90

			ChatClientRequest request = createRequestWithContextCreator(messages);
			mockCompactModelReturns("<summary>Summary</summary>");

			ChatClientRequest result = advisor.before(request, null);

			assertThat(result).isNotSameAs(request);
		}

		@Test
		@DisplayName("仅 SystemMessage 的 token 估算")
		void systemMessage_tokenEstimation() {
			SystemMessage sm = new SystemMessage(stringOfLength(400));
			List<Message> messages = List.of(sm);
			// 400 chars → 100 tokens ≥ 90
			// 但没有其他类型 messages，不做压缩

			ChatClientRequest request = createRequestWithContextCreator(messages);

			ChatClientRequest result = advisor.before(request, null);

			assertThat(result).isSameAs(request);
			verifyNoInteractions(mockCompactModel);
		}

		@Test
		@DisplayName("混合消息类型的 token 估算累加")
		void mixedMessageTypes_tokensAccumulate() {
			List<Message> messages = List.of(
					new UserMessage(stringOfLength(100)),       // 100 chars
					new AssistantMessage(stringOfLength(100)),  // 100 chars
					new SystemMessage(stringOfLength(100)),     // 100 chars
					ToolResponseMessage.builder()
							.responses(List.of(new ToolResponseMessage.ToolResponse("id1", "tool1",
									stringOfLength(100))))
							.build() // 100 chars
			);
			// total = 400 chars → 100 tokens ≥ 90

			ChatClientRequest request = createRequestWithContextCreator(messages);
			mockCompactModelReturns("<summary>Summary</summary>");

			ChatClientRequest result = advisor.before(request, null);

			assertThat(result).isNotSameAs(request);
		}
	}

	// ==================== getName / getOrder 测试 ====================

	@Nested
	@DisplayName("基本属性")
	class BasicProperties {

		@Test
		@DisplayName("getOrder 返回配置的 order")
		void getOrder_returnsConfiguredOrder() {
			assertThat(advisor.getOrder()).isEqualTo(300);
		}
	}

	// ==================== Builder 测试 ====================

	@Nested
	@DisplayName("Builder")
	class BuilderTests {

		@Test
		@DisplayName("Builder 设置所有参数")
		void builderWithAllParams() {
			AutoCompactAdvisor customAdvisor = AutoCompactAdvisor.builder(mockCompactModel)
					.contextWindow(200_000)
					.autoCompactBufferTokens(20_000)
					.maxOutputTokens(20_000)
					.maxConsecutiveFailures(5)
					.baseCompactPrompt("This is base compact prompt.")
					.customInstructions("This is custom instructions.")
					.order(100)
					.build();

			assertThat(customAdvisor.getOrder()).isEqualTo(100);
		}

		@Test
		@DisplayName("Builder 传 null compactModel 时抛出 NullPointerException")
		void builderWithNullModel_throwsNPE() {
			assertThatThrownBy(() -> AutoCompactAdvisor.builder(null))
					.isInstanceOf(NullPointerException.class)
					.hasMessageContaining("compactModel must not be null");
		}
	}

	// ==================== AutoCompactTrackingState 测试 ====================

	@Nested
	@DisplayName("AutoCompactTrackingState")
	class TrackingStateTests {

		@Test
		@DisplayName("toString 包含所有字段")
		void toString_containsAllFields() {
			AutoCompactAdvisor.AutoCompactTrackingState state =
					new AutoCompactAdvisor.AutoCompactTrackingState(true, 2, 5);
			String str = state.toString();

			assertThat(str).contains("compacted=true");
			assertThat(str).contains("consecutiveFailures=2");
			assertThat(str).contains("turnCounter=5");
		}

		@Test
		@DisplayName("字段值正确设置")
		void fieldsCorrectlySet() {
			AutoCompactAdvisor.AutoCompactTrackingState state =
					new AutoCompactAdvisor.AutoCompactTrackingState(false, 3, 10);

			assertThat(state.isCompacted()).isFalse();
			assertThat(state.getConsecutiveFailures()).isEqualTo(3);
			assertThat(state.getTurnCounter()).isEqualTo(10);
		}
	}

	// ==================== 集成场景测试 ====================

	@Nested
	@DisplayName("集成场景")
	class IntegrationScenarios {

		@Test
		@DisplayName("完整的 before + after 流程：压缩成功后 after 增加 turnCounter")
		void fullFlow_compactionThenAfter() {
			// 1. before: 触发压缩
			List<Message> messages = List.of(new UserMessage(stringOfLength(500)));
			ChatClientRequest request = createRequestWithContextCreator(messages);
			mockCompactModelReturns("<summary>Summary</summary>");

			ChatClientRequest afterBefore = advisor.before(request, null);

			// 验证 before 输出
			AutoCompactAdvisor.AutoCompactTrackingState beforeState =
					(AutoCompactAdvisor.AutoCompactTrackingState) afterBefore.context()
							.get(AutoCompactAdvisor.TRACKING_STATE_KEY);
			assertThat(beforeState.isCompacted()).isTrue();
			assertThat(beforeState.getTurnCounter()).isEqualTo(0);

			// 2. after: 增加 turnCounter
			ChatClientResponse response = ChatClientResponse.builder()
					.chatResponse(new ChatResponse(List.of(
							new Generation(new AssistantMessage("response text")))))
					.context(AutoCompactAdvisor.TRACKING_STATE_KEY, beforeState)
					.build();

			ChatClientResponse afterResponse = advisor.after(response, null);

			AutoCompactAdvisor.AutoCompactTrackingState afterState =
					(AutoCompactAdvisor.AutoCompactTrackingState) afterResponse.context()
							.get(AutoCompactAdvisor.TRACKING_STATE_KEY);
			assertThat(afterState.isCompacted()).isTrue();
			assertThat(afterState.getTurnCounter()).isEqualTo(1);
		}

		@Test
		@DisplayName("连续失败达到上限后，后续请求被 circuit breaker 阻止")
		void consecutiveFailures_thenCircuitBreakerBlocks() {
			List<Message> messages = List.of(new UserMessage(stringOfLength(500)));

			// 模拟 3 次连续失败
			mockCompactModelThrows(new RuntimeException("Error"));

			// 第 1 次失败
			ChatClientRequest req1 = createRequestWithContextCreator(messages);
			ChatClientRequest res1 = advisor.before(req1, null);
			AutoCompactAdvisor.AutoCompactTrackingState state1 =
					(AutoCompactAdvisor.AutoCompactTrackingState) res1.context()
							.get(AutoCompactAdvisor.TRACKING_STATE_KEY);
			assertThat(state1.getConsecutiveFailures()).isEqualTo(1);

			// 第 2 次失败——带上前一次的 tracking state
			ChatClientRequest req2 = createRequestWithContext(messages,
					AutoCompactAdvisor.TRACKING_STATE_KEY, state1);
			ChatClientRequest res2 = advisor.before(req2, null);
			AutoCompactAdvisor.AutoCompactTrackingState state2 =
					(AutoCompactAdvisor.AutoCompactTrackingState) res2.context()
							.get(AutoCompactAdvisor.TRACKING_STATE_KEY);
			assertThat(state2.getConsecutiveFailures()).isEqualTo(2);

			// 第 3 次失败
			ChatClientRequest req3 = createRequestWithContext(messages,
					AutoCompactAdvisor.TRACKING_STATE_KEY, state2);
			ChatClientRequest res3 = advisor.before(req3, null);
			AutoCompactAdvisor.AutoCompactTrackingState state3 =
					(AutoCompactAdvisor.AutoCompactTrackingState) res3.context()
							.get(AutoCompactAdvisor.TRACKING_STATE_KEY);
			assertThat(state3.getConsecutiveFailures()).isEqualTo(3);

			// 第 4 次——应被 circuit breaker 阻止，不再调用 compact model
			reset(mockCompactModel);
			ChatClientRequest req4 = createRequestWithContext(messages,
					AutoCompactAdvisor.TRACKING_STATE_KEY, state3);
			ChatClientRequest res4 = advisor.before(req4, null);

			assertThat(res4).isSameAs(req4);
			verifyNoInteractions(mockCompactModel);
		}

		@Test
		@DisplayName("context 中 tracking key 存放非 TrackingState 类型值时按默认状态处理")
		void whenContextHasWrongTypeForTrackingState_treatedAsDefaultState() {
			List<Message> messages = List.of(new UserMessage(stringOfLength(500)));
			ChatClientRequest request = createRequestWithContext(messages,
					AutoCompactAdvisor.TRACKING_STATE_KEY, "invalid_type");

			mockCompactModelReturns("<summary>Summary</summary>");

			ChatClientRequest result = advisor.before(request, null);

			// 应正常触发压缩（仅一次）
			assertThat(result).isNotSameAs(request);
			verify(mockCompactModel).call(any(Prompt.class));
		}
	}

	@Test
	@DisplayName("test advisorSpecConsumer")
	void advisorSpecConsumer() {
		advisor.advisorSpecConsumer().accept(new DefaultChatClient.DefaultAdvisorSpec());
	}
}
