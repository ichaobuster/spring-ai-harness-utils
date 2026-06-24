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
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ToolResultBudgetAdvisor 单元测试。
 * <p>
 * 覆盖场景：
 * <ul>
 *   <li>Builder 参数校验与默认值</li>
 *   <li>空/null prompt 短路</li>
 *   <li>小于阈值不压缩</li>
 *   <li>单条超限压缩（维度1）</li>
 *   <li>聚合超预算压缩（维度2）</li>
 *   <li>已压缩内容识别（frozen）</li>
 *   <li>skipToolNames 豁免</li>
 *   <li>多组分隔（Assistant 消息边界）</li>
 *   <li>after() 直接透传</li>
 *   <li>getName / getOrder</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ToolResultBudgetAdvisorTest {

	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private ValueOperations<String, String> valueOps;

	@Mock
	private AdvisorChain advisorChain;

	// 常用的小阈值，方便测试触发
	private static final int SMALL_SINGLE_LIMIT = 100;
	private static final int SMALL_GROUP_BUDGET = 300;
	private static final Duration TEST_TTL = Duration.ofMinutes(10);

	private ToolResultBudgetAdvisor advisor;

	@BeforeEach
	void setUp() {
		lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);

		advisor = ToolResultBudgetAdvisor.builder(redisTemplate)
				.maxSingleResultChars(SMALL_SINGLE_LIMIT)
				.maxPerGroupBudgetChars(SMALL_GROUP_BUDGET)
				.ttl(TEST_TTL)
				.previewSize(2000)
				.order(5)
				.chatMemory(mock(ChatMemory.class))
				.build();
	}

	// ==================== Builder 测试 ====================

	@Nested
	@DisplayName("Builder")
	class BuilderTests {

		@Test
		@DisplayName("redisTemplate 为 null 抛异常")
		void shouldThrowOnNullRedisTemplate() {
			assertThrows(NullPointerException.class,
					() -> ToolResultBudgetAdvisor.builder(null));
		}

		@Test
		@DisplayName("自定义 order")
		void shouldRespectCustomOrder() {
			assertEquals(5, advisor.getOrder());
		}

		@Test
		@DisplayName("ttl 为 null 抛异常")
		void shouldThrowOnNullTtl() {
			assertThrows(NullPointerException.class,
					() -> ToolResultBudgetAdvisor.builder(redisTemplate).ttl(null));
		}

		@Test
		@DisplayName("skipToolNames 批量添加")
		void shouldAcceptSkipToolNamesCollection() {
			ToolResultBudgetAdvisor a = ToolResultBudgetAdvisor.builder(redisTemplate)
					.skipToolNames(List.of("tool1", "tool2"))
					.build();
			assertNotNull(a);
		}

		@Test
		@DisplayName("默认常量值正确")
		void shouldHaveCorrectDefaultConstants() {
			assertEquals(50_000, ToolResultBudgetAdvisor.DEFAULT_MAX_SINGLE_RESULT_CHARS);
			assertEquals(200_000, ToolResultBudgetAdvisor.DEFAULT_MAX_PER_GROUP_BUDGET_CHARS);
			assertEquals(Duration.ofHours(1), ToolResultBudgetAdvisor.DEFAULT_TTL);
		}
	}

	// ==================== before() 短路场景 ====================

	@Nested
	@DisplayName("before() - 短路场景")
	class ShortCircuitTests {

		@Test
		@DisplayName("instructions 为 null 直接返回原 request")
		void shouldReturnOriginalWhenInstructionsIsNull() {
			// Prompt(List) 不接受 null，用 mock
			Prompt prompt = mock(Prompt.class);
			when(prompt.getInstructions()).thenReturn(null);
			ChatClientRequest request = new ChatClientRequest(prompt, Map.of());
			ChatClientRequest result = advisor.before(request, advisorChain);
			assertSame(request, result);
		}

		@Test
		@DisplayName("没有 ToolResponseMessage 不做任何压缩")
		void shouldReturnOriginalWhenNoToolMessages() {
			List<Message> messages = List.of(new UserMessage("hello"));
			ChatClientRequest request = new ChatClientRequest(new Prompt(messages, new OpenAiChatOptions()), Map.of());
			ChatClientRequest result = advisor.before(request, advisorChain);
			assertSame(request, result);
			verifyNoInteractions(valueOps);
		}
	}

	// ==================== 不触发压缩的场景 ====================

	@Nested
	@DisplayName("before() - 不触发压缩")
	class NoBudgetExceedTests {

		@Test
		@DisplayName("单条 tool result 小于两个阈值，不压缩")
		void shouldNotCompressSmallResult() {
			String smallData = "a".repeat(50); // 50 < SMALL_SINGLE_LIMIT(100)
			ToolResponseMessage trm = buildToolResponseMessage("id1", "myTool", smallData);
			List<Message> messages = List.of(trm);
			ChatClientRequest request = new ChatClientRequest(new Prompt(messages, new OpenAiChatOptions()), Map.of());

			ChatClientRequest result = advisor.before(request, advisorChain);
			assertSame(request, result);
			verifyNoInteractions(valueOps);
		}

		@Test
		@DisplayName("多条 tool results 聚合未超预算，不压缩")
		void shouldNotCompressWhenAggregateUnderBudget() {
			// 3 x 90 = 270 < SMALL_GROUP_BUDGET(300)，且每条 90 < SMALL_SINGLE_LIMIT(100)
			List<Message> messages = List.of(
					buildToolResponseMessage("id1", "tool", "x".repeat(90)),
					buildToolResponseMessage("id2", "tool", "y".repeat(90)),
					buildToolResponseMessage("id3", "tool", "z".repeat(90))
			);
			ChatClientRequest request = new ChatClientRequest(new Prompt(messages, new OpenAiChatOptions()), Map.of());

			ChatClientRequest result = advisor.before(request, advisorChain);
			assertSame(request, result);
			verifyNoInteractions(valueOps);
		}
	}

	// ==================== 维度 1：单条超限 ====================

	@Nested
	@DisplayName("before() - 单条超限压缩")
	class SingleResultExceedTests {

		@Test
		@DisplayName("单条超过 maxSingleResultChars 被压缩")
		void shouldCompressSingleLargeResult() {
			String largeData = "L".repeat(200); // 200 > SMALL_SINGLE_LIMIT(100)
			ToolResponseMessage trm = buildToolResponseMessage("id1", "myTool", largeData);
			List<Message> messages = List.of(trm);
			ChatClientRequest request = new ChatClientRequest(new Prompt(messages, new OpenAiChatOptions()), Map.of());

			ChatClientRequest result = advisor.before(request, advisorChain);

			// 验证 Redis 存入
			verify(valueOps).set(startsWith("tool_result:"), eq(largeData), eq(TEST_TTL));

			// 验证替换后的消息
			assertNotSame(request, result);
			List<Message> updatedMessages = result.prompt().getInstructions();
			assertEquals(1, updatedMessages.size());
			ToolResponseMessage updated = (ToolResponseMessage) updatedMessages.get(0);
			String replacedContent = updated.getResponses().get(0).responseData();
			assertTrue(replacedContent.startsWith("<persisted-output>"));
			assertTrue(replacedContent.contains("toolResultBudgetTool"));
			assertTrue(replacedContent.contains("tool_result:"));
		}

		@Test
		@DisplayName("单条超限时，同组的小结果不被压缩")
		void shouldOnlyCompressLargeOneInGroup() {
			String large = "L".repeat(200);
			String small = "s".repeat(50);
			List<Message> messages = List.of(
					buildToolResponseMessage("id1", "tool", large),
					buildToolResponseMessage("id2", "tool", small)
			);
			ChatClientRequest request = new ChatClientRequest(new Prompt(messages, new OpenAiChatOptions()), Map.of());

			ChatClientRequest result = advisor.before(request, advisorChain);

			// 只存了 1 条到 Redis
			verify(valueOps, times(1)).set(anyString(), anyString(), any(Duration.class));

			List<Message> updated = result.prompt().getInstructions();
			ToolResponseMessage trm1 = (ToolResponseMessage) updated.get(0);
			ToolResponseMessage trm2 = (ToolResponseMessage) updated.get(1);

			assertTrue(trm1.getResponses().get(0).responseData().startsWith("<persisted-output>"));
			assertEquals(small, trm2.getResponses().get(0).responseData());
		}
	}

	// ==================== 维度 2：聚合超预算 ====================

	@Nested
	@DisplayName("before() - 聚合超预算压缩")
	class AggregateExceedTests {

		@Test
		@DisplayName("聚合超预算时，从大到小压缩直到预算内")
		void shouldCompressLargestFirstUntilUnderBudget() {
			// 每条 90 chars（都 < 100 单条限），总 360 > 300 预算
			// 应该压缩最大的那条（都一样大，压缩第一个被排序选中的即可）
			List<Message> messages = List.of(
					buildToolResponseMessage("id1", "tool", "a".repeat(90)),
					buildToolResponseMessage("id2", "tool", "b".repeat(90)),
					buildToolResponseMessage("id3", "tool", "c".repeat(90)),
					buildToolResponseMessage("id4", "tool", "d".repeat(90))
			);
			ChatClientRequest request = new ChatClientRequest(new Prompt(messages, new OpenAiChatOptions()), Map.of());

			ChatClientRequest result = advisor.before(request, advisorChain);

			// 至少 1 条被压缩（360 - 90 = 270 < 300）
			verify(valueOps, atLeast(1)).set(anyString(), anyString(), any(Duration.class));
			assertNotSame(request, result);
		}

		@Test
		@DisplayName("不同大小的 results，优先压缩最大的")
		void shouldCompressLargestFirst() {
			// 60 + 80 + 95 = 235 < 300（不触发聚合），但加上更大的 → 超预算
			// 总 = 60 + 80 + 95 + 95 = 330 > 300，需要压缩 95 的那条(s)
			List<Message> messages = List.of(
					buildToolResponseMessage("idSmall", "tool", "s".repeat(60)),
					buildToolResponseMessage("idMed", "tool", "m".repeat(80)),
					buildToolResponseMessage("idLarge1", "tool", "L".repeat(95)),
					buildToolResponseMessage("idLarge2", "tool", "X".repeat(95))
			);
			ChatClientRequest request = new ChatClientRequest(new Prompt(messages, new OpenAiChatOptions()), Map.of());

			ChatClientRequest result = advisor.before(request, advisorChain);

			// 应该压缩了 1-2 条 95 char 的
			verify(valueOps, atLeast(1)).set(anyString(), anyString(), any(Duration.class));

			List<Message> updated = result.prompt().getInstructions();
			// 小的那条应该保持不变
			ToolResponseMessage smallTrm = (ToolResponseMessage) updated.get(0);
			assertEquals("s".repeat(60), smallTrm.getResponses().get(0).responseData());
		}
	}

	// ==================== 已压缩内容识别 ====================

	@Nested
	@DisplayName("before() - 已压缩内容识别")
	class AlreadyCompressedTests {

		@Test
		@DisplayName("已压缩的 content 不被再次处理，计入 frozen 开销")
		void shouldSkipAlreadyCompressedContent() {
			String alreadyCompressed = "<persisted-output>\nPreviously compressed content\n</persisted-output>";
			String freshData = "f".repeat(50);
			List<Message> messages = List.of(
					buildToolResponseMessage("id1", "tool", alreadyCompressed),
					buildToolResponseMessage("id2", "tool", freshData)
			);
			ChatClientRequest request = new ChatClientRequest(new Prompt(messages, new OpenAiChatOptions()), Map.of());

			ChatClientRequest result = advisor.before(request, advisorChain);

			// frozen + fresh = alreadyCompressed.length() + 50 可能超过/不超过 300
			// 关键是：id1 不应该被再次存入 Redis
			// 只有 id2 可能被处理（如果超预算）
			// 这里 frozen=70 + fresh=50 = 120 < 300，不触发聚合压缩
			// 50 < 100 不触发单条压缩
			assertSame(request, result);
			verifyNoInteractions(valueOps);
		}

		@Test
		@DisplayName("已压缩内容计入 frozen，推动聚合超预算")
		void shouldCountCompressedAsFrozenSize() {
			// frozen size = 250, fresh size = 90 → total 340 > 300 → 应该压缩 fresh
			String alreadyCompressed = "<persisted-output>\n" + "x".repeat(230) + "\n</persisted-output>";
			String freshData = "f".repeat(90);
			List<Message> messages = List.of(
					buildToolResponseMessage("id1", "tool", alreadyCompressed),
					buildToolResponseMessage("id2", "tool", freshData)
			);
			ChatClientRequest request = new ChatClientRequest(new Prompt(messages, new OpenAiChatOptions()), Map.of());

			ChatClientRequest result = advisor.before(request, advisorChain);

			// fresh(90 chars) 应该被压缩因为 frozen(~250) + fresh(90) > 300
			verify(valueOps, times(1)).set(anyString(), eq(freshData), eq(TEST_TTL));
			assertNotSame(request, result);
		}
	}

	// ==================== skipToolNames ====================

	@Nested
	@DisplayName("before() - skipToolNames")
	class SkipToolNameTests {

		@Test
		@DisplayName("skipToolNames 中的工具结果不被压缩")
		void shouldSkipToolInSkipList() {
			ToolResultBudgetAdvisor advisorWithSkip = ToolResultBudgetAdvisor.builder(redisTemplate)
					.maxSingleResultChars(SMALL_SINGLE_LIMIT)
					.maxPerGroupBudgetChars(SMALL_GROUP_BUDGET)
					.ttl(TEST_TTL)
					.skipToolName("toolResultBudgetTool")
					.build();

			// 即使超过单条限制，也因为在 skip 列表中而不压缩
			String largeData = "L".repeat(200);
			ToolResponseMessage trm = buildToolResponseMessage("id1", "toolResultBudgetTool", largeData);
			List<Message> messages = List.of(trm);
			ChatClientRequest request = new ChatClientRequest(new Prompt(messages, new OpenAiChatOptions()), Map.of());

			ChatClientRequest result = advisorWithSkip.before(request, advisorChain);

			assertSame(request, result);
			verifyNoInteractions(valueOps);
		}

		@Test
		@DisplayName("skip 的工具结果不计入聚合预算")
		void shouldNotCountSkippedToolInBudget() {
			ToolResultBudgetAdvisor advisorWithSkip = ToolResultBudgetAdvisor.builder(redisTemplate)
					.maxSingleResultChars(SMALL_SINGLE_LIMIT)
					.maxPerGroupBudgetChars(SMALL_GROUP_BUDGET)
					.ttl(TEST_TTL)
					.skipToolName("skipMe")
					.build();

			// skipped: 200 chars (不计入预算)
			// normal: 90 chars (计入预算，90 < 300，不触发聚合)
			List<Message> messages = List.of(
					buildToolResponseMessage("id1", "skipMe", "S".repeat(200)),
					buildToolResponseMessage("id2", "normalTool", "n".repeat(90))
			);
			ChatClientRequest request = new ChatClientRequest(new Prompt(messages, new OpenAiChatOptions()), Map.of());

			ChatClientRequest result = advisorWithSkip.before(request, advisorChain);

			// 没有任何压缩发生
			assertSame(request, result);
			verifyNoInteractions(valueOps);
		}
	}

	// ==================== Assistant 分组边界 ====================

	@Nested
	@DisplayName("before() - Assistant 分组边界")
	class GroupingTests {

		@Test
		@DisplayName("不同 Assistant 分隔的组独立计算预算")
		void shouldPartitionGroupsByAssistantMessages() {
			// Group 1: 2 x 90 = 180 < 300 → 不压缩
			// [Assistant boundary]
			// Group 2: 2 x 90 = 180 < 300 → 不压缩
			// [Assistant boundary]
			// Group 3: 2 x 90 = 180 < 300 → 不压缩
			AssistantMessage assistant1 = new AssistantMessage("thinking...");
			AssistantMessage assistant2 = AssistantMessage.builder()
					.content("thinking...")
					.properties(Map.of("id", "1234567890"))
					.build();

			List<Message> messages = List.of(
					buildToolResponseMessage("id1", "tool", "a".repeat(90)),
					buildToolResponseMessage("id2", "tool", "b".repeat(90)),
					assistant1,
					buildToolResponseMessage("id3", "tool", "c".repeat(90)),
					buildToolResponseMessage("id4", "tool", "d".repeat(90)),
					assistant2,
					buildToolResponseMessage("id5", "tool", "e".repeat(90)),
					buildToolResponseMessage("id6", "tool", "f".repeat(90))
			);
			ChatClientRequest request = new ChatClientRequest(new Prompt(messages, new OpenAiChatOptions()), Map.of());

			ChatClientRequest result = advisor.before(request, advisorChain);

			// 不触发压缩（每组 180 < 300）
			assertSame(request, result);
			verifyNoInteractions(valueOps);
		}

		@Test
		@DisplayName("同样ID的 Assistant 分隔时，所有工具结果在同一组")
		void shouldGroupAllToolResultsTogetherWithSameIdAssistant() {
			AssistantMessage assistant1 = AssistantMessage.builder()
					.content("thinking 1 ...")
					.properties(Map.of("id", "1234567890"))
					.build();
			AssistantMessage assistant2 = AssistantMessage.builder()
					.content("thinking 2 ...")
					.properties(Map.of("id", "1234567890"))
					.build();
			// 后4条在一组: 4 x 90 = 360 > 300 → 应该压缩
			List<Message> messages = List.of(
					buildToolResponseMessage("id1", "tool", "a".repeat(90)),
					assistant1,
					buildToolResponseMessage("id2", "tool", "b".repeat(90)),
					buildToolResponseMessage("id3", "tool", "c".repeat(90)),
					buildToolResponseMessage("id4", "tool", "d".repeat(90)),
					assistant2,
					buildToolResponseMessage("id5", "tool", "e".repeat(90))
			);
			ChatClientRequest request = new ChatClientRequest(new Prompt(messages, new OpenAiChatOptions()), Map.of());

			ChatClientRequest result = advisor.before(request, advisorChain);

			assertNotSame(request, result);
			verify(valueOps, atLeast(1)).set(anyString(), anyString(), any(Duration.class));
		}

		@Test
		@DisplayName("没有 Assistant 分隔时，所有工具结果在同一组")
		void shouldGroupAllToolResultsTogetherWithoutAssistant() {
			// 所有在一组: 4 x 90 = 360 > 300 → 应该压缩
			List<Message> messages = List.of(
					buildToolResponseMessage("id1", "tool", "a".repeat(90)),
					buildToolResponseMessage("id2", "tool", "b".repeat(90)),
					buildToolResponseMessage("id3", "tool", "c".repeat(90)),
					buildToolResponseMessage("id4", "tool", "d".repeat(90))
			);
			ChatClientRequest request = new ChatClientRequest(new Prompt(messages, new OpenAiChatOptions()), Map.of());

			ChatClientRequest result = advisor.before(request, advisorChain);

			assertNotSame(request, result);
			verify(valueOps, atLeast(1)).set(anyString(), anyString(), any(Duration.class));
		}
	}

	// ==================== 空/null 数据边界 ====================

	@Nested
	@DisplayName("before() - 边界情况")
	class EdgeCaseTests {

		@Test
		@DisplayName("tool result 内容为 null 不压缩")
		void shouldHandleNullResponseData() {
			ToolResponseMessage trm = buildToolResponseMessage("id1", "tool", null);
			List<Message> messages = List.of(trm);
			ChatClientRequest request = new ChatClientRequest(new Prompt(messages, new OpenAiChatOptions()), Map.of());

			ChatClientRequest result = advisor.before(request, advisorChain);
			assertSame(request, result);
			verifyNoInteractions(valueOps);
		}

		@Test
		@DisplayName("tool result 内容为空字符串不压缩")
		void shouldHandleEmptyResponseData() {
			ToolResponseMessage trm = buildToolResponseMessage("id1", "tool", "");
			List<Message> messages = List.of(trm);
			ChatClientRequest request = new ChatClientRequest(new Prompt(messages, new OpenAiChatOptions()), Map.of());

			ChatClientRequest result = advisor.before(request, advisorChain);
			assertSame(request, result);
			verifyNoInteractions(valueOps);
		}

		@Test
		@DisplayName("混合消息类型正确处理")
		void shouldHandleMixedMessageTypes() {
			String large = "L".repeat(200);
			List<Message> messages = List.of(
					new UserMessage("hi"),
					buildToolResponseMessage("id1", "tool", large),
					new UserMessage("bye")
			);
			ChatClientRequest request = new ChatClientRequest(new Prompt(messages, new OpenAiChatOptions()), Map.of());

			ChatClientRequest result = advisor.before(request, advisorChain);

			assertNotSame(request, result);
			List<Message> updated = result.prompt().getInstructions();
			assertEquals(3, updated.size());
			// UserMessage 保持不变
			assertTrue(updated.get(0) instanceof UserMessage);
			assertTrue(updated.get(2) instanceof UserMessage);
			// ToolResponseMessage 被替换
			ToolResponseMessage trm = (ToolResponseMessage) updated.get(1);
			assertTrue(trm.getResponses().get(0).responseData().startsWith("<persisted-output>"));
		}
	}

	// ==================== 替换文本格式 ====================

	@Nested
	@DisplayName("替换文本格式")
	class ReplacementTextTests {

		@Test
		@DisplayName("替换文本包含 preview、redisKey、工具名提示")
		void shouldBuildCorrectReplacementText() {
			String data = "H".repeat(200);
			ToolResponseMessage trm = buildToolResponseMessage("id1", "myTool", data);
			List<Message> messages = List.of(trm);
			ChatClientRequest request = new ChatClientRequest(new Prompt(messages, new OpenAiChatOptions()), Map.of());

			ChatClientRequest result = advisor.before(request, advisorChain);

			ToolResponseMessage updated = (ToolResponseMessage) result.prompt().getInstructions().get(0);
			String replacement = updated.getResponses().get(0).responseData();

			assertTrue(replacement.startsWith("<persisted-output>"));
			assertTrue(replacement.contains("</persisted-output>"));
			assertTrue(replacement.contains("Output too large"));
			assertTrue(replacement.contains("Preview"));
			assertTrue(replacement.contains("toolResultBudgetTool"));
			assertTrue(replacement.contains("tool_result:"));
		}

		@Test
		@DisplayName("tool response 的 id 和 name 保留")
		void shouldPreserveToolResponseIdAndName() {
			String data = "X".repeat(200);
			ToolResponseMessage trm = buildToolResponseMessage("myUniqueId", "myToolName", data);
			List<Message> messages = List.of(trm);
			ChatClientRequest request = new ChatClientRequest(new Prompt(messages, new OpenAiChatOptions()), Map.of());

			ChatClientRequest result = advisor.before(request, advisorChain);

			ToolResponseMessage updated = (ToolResponseMessage) result.prompt().getInstructions().get(0);
			ToolResponseMessage.ToolResponse tr = updated.getResponses().get(0);
			assertEquals("myUniqueId", tr.id());
			assertEquals("myToolName", tr.name());
		}
	}

	// ==================== after() / getName() / getOrder() ====================

	@Nested
	@DisplayName("after() / getName() / getOrder()")
	class MetadataTests {

		@Test
		@DisplayName("after() 直接透传 response")
		void shouldPassThroughResponse() {
			ChatClientResponse response = ChatClientResponse.builder().build();
			ChatClientResponse result = advisor.after(response, advisorChain);
			assertSame(response, result);
		}

		@Test
		@DisplayName("getName() 返回正确名称")
		void shouldReturnCorrectName() {
			assertEquals("ToolResultBudgetAdvisor", advisor.getName());
		}

		@Test
		@DisplayName("getOrder() 返回配置值")
		void shouldReturnConfiguredOrder() {
			assertEquals(5, advisor.getOrder());
		}
	}

	// ==================== 两个维度联合触发 ====================

	@Nested
	@DisplayName("before() - 两个维度联合")
	class CombinedDimensionTests {

		@Test
		@DisplayName("单条超限 + 聚合超预算同时生效")
		void shouldHandleBothDimensionsSimultaneously() {
			// id1: 200 chars → 超单条限(100)，维度1入选
			// 聚合: id2-id5: 4 x 90 = 360 > 300 → 维度2也需要压缩更多
			List<Message> messages = List.of(
					buildToolResponseMessage("id1", "tool", "L".repeat(200)),
					buildToolResponseMessage("id2", "tool", "a".repeat(90)),
					buildToolResponseMessage("id3", "tool", "b".repeat(90)),
					buildToolResponseMessage("id4", "tool", "c".repeat(90)),
					buildToolResponseMessage("id5", "tool", "d".repeat(90))
			);
			ChatClientRequest request = new ChatClientRequest(new Prompt(messages, new OpenAiChatOptions()), Map.of());

			ChatClientRequest result = advisor.before(request, advisorChain);

			// id1 因维度1入选，维度2还需从剩余中选
			verify(valueOps, times(2)).set(anyString(), anyString(), any(Duration.class));
			assertNotSame(request, result);
		}
	}

	// ==================== Redis key 格式验证 ====================

	@Nested
	@DisplayName("Redis key 格式")
	class RedisKeyTests {

		@Test
		@DisplayName("Redis key 以 tool_result: 前缀开头")
		void shouldUseCorrectRedisKeyPrefix() {
			String data = "D".repeat(200);
			ToolResponseMessage trm = buildToolResponseMessage("id1", "tool", data);
			List<Message> messages = List.of(trm);
			ChatClientRequest request = new ChatClientRequest(new Prompt(messages, new OpenAiChatOptions()), Map.of());

			advisor.before(request, advisorChain);

			ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
			verify(valueOps).set(keyCaptor.capture(), eq(data), eq(TEST_TTL));
			assertTrue(keyCaptor.getValue().startsWith("tool_result:"));
		}
	}

	// ==================== 辅助方法 ====================

	/**
	 * 构建一个包含单条 ToolResponse 的 ToolResponseMessage。
	 */
	private ToolResponseMessage buildToolResponseMessage(String id, String name, String data) {
		ToolResponseMessage.ToolResponse tr = new ToolResponseMessage.ToolResponse(id, name, data);
		return ToolResponseMessage.builder()
				.responses(List.of(tr))
				.metadata(Map.of())
				.build();
	}

	@Test
	@DisplayName("after - bypass")
	public void testAfter() {
		ChatClientResponse response = ChatClientResponse.builder().build();
		ChatClientResponse result = advisor.after(response, advisorChain);
		assertSame(response, result);
	}
}
