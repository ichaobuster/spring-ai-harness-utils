package io.github.springai.harness.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolResultBudgetToolTest {

	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private ValueOperations<String, String> valueOperations;

	private ToolResultBudgetTool tool;

	private static final String REDIS_KEY_PREFIX = ToolResultBudgetTool.REDIS_KEY_PREFIX;
	private static final String VALID_KEY = REDIS_KEY_PREFIX + "test-key-123";
	private static final String TEST_CONTENT = "This is a long tool result content that was stored in Redis due to its length.";

	@BeforeEach
	void setUp() {
		tool = new ToolResultBudgetTool(redisTemplate);
	}

	@Test
	void testApply_WithValidKey_ReturnsContent() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get(VALID_KEY)).thenReturn(TEST_CONTENT);

		ToolResultBudgetTool.Request request = new ToolResultBudgetTool.Request(VALID_KEY);
		String result = tool.apply(request);

		assertThat(result).isEqualTo(TEST_CONTENT);
	}

	@Test
	void testApply_WithNullRequest_ReturnsErrorMessage() {
		String result = tool.apply(null);

		assertThat(result).startsWith("Error: redisKey must not be null or empty.");
	}

	@Test
	void testApply_WithNullRedisKey_ReturnsErrorMessage() {
		ToolResultBudgetTool.Request request = new ToolResultBudgetTool.Request(null);
		String result = tool.apply(request);

		assertThat(result).startsWith("Error: redisKey must not be null or empty.");
	}

	@Test
	void testApply_WithEmptyRedisKey_ReturnsErrorMessage() {
		ToolResultBudgetTool.Request request = new ToolResultBudgetTool.Request("");
		String result = tool.apply(request);

		assertThat(result).startsWith("Error: redisKey must not be null or empty.");
	}

	@Test
	void testApply_WithBlankRedisKey_ReturnsErrorMessage() {
		ToolResultBudgetTool.Request request = new ToolResultBudgetTool.Request("   ");
		String result = tool.apply(request);

		assertThat(result).startsWith("Error: redisKey must not be null or empty.");
	}

	@Test
	void testApply_WithInvalidKeyPrefix_ReturnsErrorMessage() {
		String invalidKey = "invalid:some-key";
		ToolResultBudgetTool.Request request = new ToolResultBudgetTool.Request(invalidKey);
		String result = tool.apply(request);

		assertThat(result).startsWith("Error: Invalid redisKey format.");
		assertThat(result).contains(REDIS_KEY_PREFIX);
	}

	@Test
	void testApply_WithKeyWithoutPrefix_ReturnsErrorMessage() {
		String invalidKey = "some-other-prefix:key";
		ToolResultBudgetTool.Request request = new ToolResultBudgetTool.Request(invalidKey);
		String result = tool.apply(request);

		assertThat(result).startsWith("Error: Invalid redisKey format.");
		assertThat(result).contains(REDIS_KEY_PREFIX);
	}

	@Test
	void testApply_WhenContentNotFound_ReturnsErrorMessage() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get(VALID_KEY)).thenReturn(null);

		ToolResultBudgetTool.Request request = new ToolResultBudgetTool.Request(VALID_KEY);
		String result = tool.apply(request);

		assertThat(result).startsWith("Error: Tool result not found or expired for key:");
		assertThat(result).contains(VALID_KEY);
	}

	@Test
	void testApply_WithDifferentValidKey_ReturnsContent() {
		String differentKey = REDIS_KEY_PREFIX + "another-key-456";
		String differentContent = "Different content here";

		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get(differentKey)).thenReturn(differentContent);

		ToolResultBudgetTool.Request request = new ToolResultBudgetTool.Request(differentKey);
		String result = tool.apply(request);

		assertThat(result).isEqualTo(differentContent);
	}

	@Test
	void testApply_WithEmptyContentInRedis() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get(VALID_KEY)).thenReturn("");

		ToolResultBudgetTool.Request request = new ToolResultBudgetTool.Request(VALID_KEY);
		String result = tool.apply(request);

		assertThat(result).isEmpty();
	}

	@Test
	void testConstants() {
		assertThat(ToolResultBudgetTool.TOOL_NAME).isEqualTo("toolResultBudgetTool");
		assertThat(ToolResultBudgetTool.REDIS_KEY_PREFIX).isEqualTo("tool_result:");
		assertThat(ToolResultBudgetTool.DESCRIPTION).isNotEmpty();
	}

	@Test
	void testCreateToolCallback() {
		ToolCallback callback = ToolResultBudgetTool.createToolCallback(redisTemplate);
		assertThat(callback).isNotNull();
	}

	@Test
	void testRequestRecord() {
		String key = "test-key";
		ToolResultBudgetTool.Request request = new ToolResultBudgetTool.Request(key);

		assertThat(request.redisKey()).isEqualTo(key);
	}

	@Test
	void testApply_WithMaliciousKeyPrefix_PassesPrefixCheck() {
		String maliciousKey = REDIS_KEY_PREFIX + "../etc/passwd";

		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get(maliciousKey)).thenReturn("malicious content");

		ToolResultBudgetTool.Request request = new ToolResultBudgetTool.Request(maliciousKey);
		String result = tool.apply(request);

		assertThat(result).isEqualTo("malicious content");
	}
}