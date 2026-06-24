package io.github.springai.harness.tool;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.context.annotation.Description;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.function.Function;

/**
 * Spring AI Function Tool: 允许 LLM 通过 Redis Key 读取被 RedisToolResultBudgetAdvisor
 * 暂存到 Redis 中的超长工具执行结果。
 * <p>
 * 注意：此工具的名称 ("toolResultBudgetTool") 应被添加到 RedisToolResultBudgetAdvisor 的
 * skipToolNames 集合中，防止此工具自身的返回值被再次压缩（形成循环依赖）。
 * <p>
 * 使用方式
 * <p>
 * 1. 注册为 Spring AI function：
 * <pre>
 * &#64;Bean
 * &#64;Description(ToolResultBudgetTool.DESCRIPTION)
 * public Function&lt;ToolResultBudgetTool.Request, String&gt; toolResultBudgetTool(StringRedisTemplate redisTemplate) {
 *     return new ToolResultBudgetTool(redisTemplate);
 * }
 * </pre>
 * <p>
 * 2. 使用 ToolCallback：
 * <pre>
 * ToolCallback toolResultBudgetToolCallback = ToolResultBudgetTool.createToolCallback(redisTemplate);
 * </pre>
 */
public class ToolResultBudgetTool implements Function<ToolResultBudgetTool.Request, String> {

	public static final String TOOL_NAME = "toolResultBudgetTool";

	public static final String DESCRIPTION = """
			Read the full content of a tool result that was too large and stored in Redis.
			Use this when you see a persisted-output tag with a redisKey.
			""";

	public static final String REDIS_KEY_PREFIX = "tool_result:";

	private final StringRedisTemplate redisTemplate;

	public ToolResultBudgetTool(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	@Override
	public String apply(Request request) {
		if (request == null || request.redisKey() == null || request.redisKey().isBlank()) {
			return "Error: redisKey must not be null or empty.";
		}
		// 安全检查：只允许读取 tool_result: 前缀的 key
		if (!request.redisKey().startsWith(REDIS_KEY_PREFIX)) {
			return "Error: Invalid redisKey format. Only keys with '" + REDIS_KEY_PREFIX + "' prefix are allowed.";
		}
		String content = redisTemplate.opsForValue().get(request.redisKey());
		if (content == null) {
			return "Error: Tool result not found or expired for key: " + request.redisKey() +
					". The result may have exceeded its TTL and been evicted from Redis.";
		}
		return content;
	}

	/**
	 * Create a ToolCallback for the tool.
	 */
	public static ToolCallback createToolCallback(StringRedisTemplate redisTemplate) {
		return FunctionToolCallback.builder(TOOL_NAME, new ToolResultBudgetTool(redisTemplate))
				.description(DESCRIPTION)
				.inputType(Request.class)
				.build();
	}

	public record Request(
			@Description("The redis key of the stored tool result to retrieve") String redisKey) {
	}
}
