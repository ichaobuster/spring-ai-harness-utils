package io.github.springai.harness.advisor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.*;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class HumanInTheLoopAdvisorTest {

	@Mock
	AdvisorChain advisorChain;

	ChatMemory chatMemory;

	@BeforeEach
	void setup() {
		chatMemory = MessageWindowChatMemory.builder()
				.maxMessages(100)
				.chatMemoryRepository(new InMemoryChatMemoryRepository())
				.build();
	}


	// -------------------------------------------------------------------------
	// Builder
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("Builder")
	class BuilderTests {

		@Test
		@DisplayName("Custom order is respected")
		void customOrder() {
			HumanInTheLoopAdvisor a = HumanInTheLoopAdvisor.builder(chatMemory).needPermissionTools(Set.of("foo")).order(42).build();
			assertThat(a.getOrder()).isEqualTo(42);
		}

		@Test
		@DisplayName("Supports various tool name input types")
		void toolCallingManager() {
			HumanInTheLoopAdvisor a = HumanInTheLoopAdvisor.builder(chatMemory)
					.toolCallingManager(ToolCallingManager.builder().build())
					.build();

			assertThat(a).isNotNull();
		}

	}

	// -------------------------------------------------------------------------
	// after()
	// -------------------------------------------------------------------------

	@Test
	@DisplayName("after() passes the response through unchanged")
	void afterPassesThrough() {
		ChatClientResponse response = ChatClientResponse.builder().context(Map.of()).build();
		HumanInTheLoopAdvisor a = HumanInTheLoopAdvisor.builder(chatMemory).needPermissionTools(Set.of("foo")).build();
		assertThat(a.after(response, advisorChain)).isSameAs(response);
	}

	// -------------------------------------------------------------------------
	// before() — tool callback interception
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("before() — interception")
	class InterceptionTests {

		@Test
		@DisplayName("Returns request unchanged when no ToolCallingChatOptions")
		void passesThoughWhenNoOptions() {
			ChatClientRequest request = request(new Prompt(new UserMessage("hi")));
			HumanInTheLoopAdvisor a = HumanInTheLoopAdvisor.builder(chatMemory).needPermissionTools(Set.of("foo")).build();

			ChatClientRequest result = a.before(request, advisorChain);

			assertThat(result).isSameAs(request);
		}

		@Test
		@DisplayName("Wraps configured tools with HITL interceptor")
		void wrapsConfiguredTools() {
			ToolCallback fooTool = FunctionToolCallback.builder("foo", (Function<Map<String, Object>, String>) (s) -> "real-foo")
					.description("foo desc")
					.inputType(Map.class)
					.build();
			ToolCallback barTool = FunctionToolCallback.builder("bar", (Function<Map<String, Object>, String>) (s) -> "real-bar")
					.description("bar desc")
					.inputType(Map.class)
					.build();

			OpenAiChatOptions opts = OpenAiChatOptions.builder()
					.toolCallbacks(new ArrayList<>(List.of(fooTool, barTool)))
					.build();
			ChatClientRequest request = request(new Prompt(new UserMessage("hi"), opts));

			// Only 'foo' requires HITL
			HumanInTheLoopAdvisor a = HumanInTheLoopAdvisor.builder(chatMemory).needPermissionTools(Set.of("foo")).build();

			ChatClientRequest result = a.before(request, advisorChain);

			OpenAiChatOptions resultOpts = (OpenAiChatOptions) result.prompt().getOptions();
			List<ToolCallback> resultCallbacks = resultOpts.getToolCallbacks();

			assertThat(resultCallbacks).hasSize(2);

			ToolCallback interceptedFoo = findTool(resultCallbacks, "foo");
			ToolCallback originalBar = findTool(resultCallbacks, "bar");

			// Verify 'foo' is wrapped
			assertThat(interceptedFoo).isNotSameAs(fooTool);
			assertThat(interceptedFoo.getToolDefinition().description()).isEqualTo("foo desc");

			// Verify 'foo' wrapper returns HITL payload and is returnDirect
			String output = interceptedFoo.call("{\"arg1\": \"val1\"}");
			assertThat(output).contains("\"hitlRequired\":").contains("true").contains("\"tool\":").contains("\"foo\"").contains("\"arg1\":").contains("\"val1\"");

			// Verify 'bar' is unchanged
			assertThat(originalBar).isSameAs(barTool);
		}

		@Test
		@DisplayName("Returns request unchanged if no configured tools are present")
		void unchangedIfNoMatch() {
			ToolCallback barTool = FunctionToolCallback.builder("bar", (Function<Map<String, Object>, String>) (s) -> "real-bar")
					.inputType(Map.class)
					.build();
			OpenAiChatOptions opts = OpenAiChatOptions.builder()
					.toolCallbacks(new ArrayList<>(List.of(barTool)))
					.build();
			ChatClientRequest request = request(new Prompt(new UserMessage("hi"), opts));

			HumanInTheLoopAdvisor a = HumanInTheLoopAdvisor.builder(chatMemory).needPermissionTools(Set.of("foo")).build();

			ChatClientRequest result = a.before(request, advisorChain);

			assertThat(result).isSameAs(request);
		}

		@Test
		@DisplayName("HITL denied")
		void hitlDenied() {
			ToolCallback fooTool = FunctionToolCallback.builder("foo", (Function<Map<String, Object>, String>) (s) -> "real-foo")
					.description("foo desc")
					.inputType(Map.class)
					.build();
			ToolCallback barTool = FunctionToolCallback.builder("bar", (Function<Map<String, Object>, String>) (s) -> "real-bar")
					.description("bar desc")
					.inputType(Map.class)
					.build();

			OpenAiChatOptions opts = OpenAiChatOptions.builder()
					.toolCallbacks(new ArrayList<>(List.of(fooTool, barTool)))
					.build();

			HumanInTheLoopAdvisor.HitlRequest hitlRequest = new HumanInTheLoopAdvisor.HitlRequest(true, "foo", Map.of("arg1", "val1"));
			HumanInTheLoopAdvisor.HitlResponse hitlResponse = new HumanInTheLoopAdvisor.HitlResponse(hitlRequest, HumanInTheLoopAdvisor.Permission.DENY);

			ChatClientRequest request = request(new Prompt(new ArrayList<>(List.of(
					new UserMessage("hi"),
					AssistantMessage.builder()
							.content("call foo")
							.toolCalls(List.of(new AssistantMessage.ToolCall("test-tool-call-id", "function", "foo", "{\"arg1\": \"val1\"}")))
							.build()
			)),
					opts), Map.of(HumanInTheLoopAdvisor.HITL_RESPONSE_KEY, hitlResponse));

			// Only 'foo' requires HITL
			HumanInTheLoopAdvisor a = HumanInTheLoopAdvisor.builder(chatMemory).needPermissionTools(Set.of("foo")).build();

			ChatClientRequest result = a.before(request, advisorChain);
			assertThat(result).isNotNull();

			// memory has tool response, content is denied
			assertThat(chatMemory.get("test-conversation").size()).isEqualTo(1);
			assertThat(chatMemory.get("test-conversation").get(0)).isInstanceOf(ToolResponseMessage.class);
			assertThat(((ToolResponseMessage) chatMemory.get("test-conversation").get(0)).getResponses().get(0).responseData()).contains("User denied permission to execute this tool.");

			OpenAiChatOptions resultOpts = (OpenAiChatOptions) result.prompt().getOptions();
			List<ToolCallback> resultCallbacks = resultOpts.getToolCallbacks();

			assertThat(resultCallbacks).hasSize(2);

			ToolCallback interceptedFoo = findTool(resultCallbacks, "foo");
			ToolCallback originalBar = findTool(resultCallbacks, "bar");

			// Verify 'foo' is wrapped
			assertThat(interceptedFoo).isNotSameAs(fooTool);
			assertThat(interceptedFoo.getToolDefinition().description()).isEqualTo("foo desc");

			// Verify 'foo' wrapper returns HITL payload and is returnDirect
			String output = interceptedFoo.call("{\"arg1\": \"val1\"}");
			assertThat(output).contains("\"hitlRequired\":").contains("true").contains("\"tool\":").contains("\"foo\"").contains("\"arg1\":").contains("\"val1\"");

			// Verify 'bar' is unchanged
			assertThat(originalBar).isSameAs(barTool);
		}
	}

	@Test
	@DisplayName("HITL once allowed")
	void hitlOnceAllowed() {
		ToolCallback fooTool = FunctionToolCallback.builder("foo", (Function<Map<String, Object>, String>) (s) -> "real-foo")
				.description("foo desc")
				.inputType(Map.class)
				.build();
		ToolCallback barTool = FunctionToolCallback.builder("bar", (Function<Map<String, Object>, String>) (s) -> "real-bar")
				.description("bar desc")
				.inputType(Map.class)
				.build();

		OpenAiChatOptions opts = OpenAiChatOptions.builder()
				.toolCallbacks(new ArrayList<>(List.of(fooTool, barTool)))
				.build();

		HumanInTheLoopAdvisor.HitlRequest hitlRequest = new HumanInTheLoopAdvisor.HitlRequest(true, "foo", Map.of("arg1", "val1"));
		HumanInTheLoopAdvisor.HitlResponse hitlResponse = new HumanInTheLoopAdvisor.HitlResponse(hitlRequest, HumanInTheLoopAdvisor.Permission.ALLOW_ONCE);

		ChatClientRequest request = request(new Prompt(new ArrayList<>(List.of(
				new UserMessage("hi"),
				AssistantMessage.builder()
						.content("call foo")
						.toolCalls(List.of(new AssistantMessage.ToolCall("test-tool-call-id", "function", "foo", "{\"arg1\": \"val1\"}")))
						.build()
		)),
				opts), Map.of(HumanInTheLoopAdvisor.HITL_RESPONSE_KEY, hitlResponse));

		// Only 'foo' requires HITL
		HumanInTheLoopAdvisor a = HumanInTheLoopAdvisor.builder(chatMemory).needPermissionTools(Set.of("foo")).build();

		ChatClientRequest result = a.before(request, advisorChain);
		assertThat(result).isNotNull();

		// memory has tool response, content is denied
		assertThat(chatMemory.get("test-conversation").size()).isEqualTo(1);
		assertThat(chatMemory.get("test-conversation").get(0)).isInstanceOf(ToolResponseMessage.class);
		assertThat(((ToolResponseMessage) chatMemory.get("test-conversation").get(0)).getResponses().get(0).responseData()).contains("real-foo");

		OpenAiChatOptions resultOpts = (OpenAiChatOptions) result.prompt().getOptions();
		List<ToolCallback> resultCallbacks = resultOpts.getToolCallbacks();

		assertThat(resultCallbacks).hasSize(2);

		ToolCallback interceptedFoo = findTool(resultCallbacks, "foo");
		ToolCallback originalBar = findTool(resultCallbacks, "bar");

		// Verify 'foo' is wrapped
		assertThat(interceptedFoo).isNotSameAs(fooTool);
		assertThat(interceptedFoo.getToolDefinition().description()).isEqualTo("foo desc");

		// Verify 'foo' wrapper returns HITL payload and is returnDirect
		String output = interceptedFoo.call("{\"arg1\": \"val1\"}");
		assertThat(output).contains("\"hitlRequired\":").contains("true").contains("\"tool\":").contains("\"foo\"").contains("\"arg1\":").contains("\"val1\"");

		// Verify 'bar' is unchanged
		assertThat(originalBar).isSameAs(barTool);
	}

	@Test
	@DisplayName("HITL always allowed")
	void hitlAlwaysAllowed() {
		ToolCallback fooTool = FunctionToolCallback.builder("foo", (Function<Map<String, Object>, String>) (s) -> "real-foo")
				.description("foo desc")
				.inputType(Map.class)
				.build();
		ToolCallback barTool = FunctionToolCallback.builder("bar", (Function<Map<String, Object>, String>) (s) -> "real-bar")
				.description("bar desc")
				.inputType(Map.class)
				.build();

		OpenAiChatOptions opts = OpenAiChatOptions.builder()
				.toolCallbacks(new ArrayList<>(List.of(fooTool, barTool)))
				.build();

		HumanInTheLoopAdvisor.HitlRequest hitlRequest = new HumanInTheLoopAdvisor.HitlRequest(true, "foo", Map.of("arg1", "val1"));
		HumanInTheLoopAdvisor.HitlResponse hitlResponse = new HumanInTheLoopAdvisor.HitlResponse(hitlRequest, HumanInTheLoopAdvisor.Permission.ALLOW_ALWAYS);

		ChatClientRequest request = request(new Prompt(new ArrayList<>(List.of(
				new UserMessage("hi"),
				AssistantMessage.builder()
						.content("call foo")
						.toolCalls(List.of(new AssistantMessage.ToolCall("test-tool-call-id", "function", "foo", "{\"arg1\": \"val1\"}")))
						.build()
		)),
				opts), Map.of(HumanInTheLoopAdvisor.HITL_RESPONSE_KEY, hitlResponse,
				HumanInTheLoopAdvisor.HITL_ALWAYS_ALLOW_TOOLS_KEY, Set.of("foo")
		));

		// Only 'foo' requires HITL
		HumanInTheLoopAdvisor a = HumanInTheLoopAdvisor.builder(chatMemory).needPermissionTools(Set.of("foo")).build();

		ChatClientRequest result = a.before(request, advisorChain);
		assertThat(result).isNotNull();

		// memory has tool response, content is denied
		assertThat(chatMemory.get("test-conversation").size()).isEqualTo(1);
		assertThat(chatMemory.get("test-conversation").get(0)).isInstanceOf(ToolResponseMessage.class);
		assertThat(((ToolResponseMessage) chatMemory.get("test-conversation").get(0)).getResponses().get(0).responseData()).contains("real-foo");

		OpenAiChatOptions resultOpts = (OpenAiChatOptions) result.prompt().getOptions();
		List<ToolCallback> resultCallbacks = resultOpts.getToolCallbacks();

		assertThat(resultCallbacks).hasSize(2);

		ToolCallback interceptedFoo = findTool(resultCallbacks, "foo");
		ToolCallback originalBar = findTool(resultCallbacks, "bar");

		// Verify 'foo' is unchanged
		assertThat(interceptedFoo).isSameAs(fooTool);
		// Verify 'bar' is unchanged
		assertThat(originalBar).isSameAs(barTool);
	}

	// -------------------------------------------------------------------------
	// helpers
	// -------------------------------------------------------------------------

	private static ChatClientRequest request(Prompt prompt) {
		return request(prompt, Map.of());
	}

	private static ChatClientRequest request(Prompt prompt, Map<String, Object> context) {
		Map<String, Object> contextWithMemory = new HashMap<>(context);
		contextWithMemory.put(ChatMemory.CONVERSATION_ID, "test-conversation");
		return ChatClientRequest.builder().prompt(prompt).context(contextWithMemory).build();
	}

	private static ToolCallback findTool(List<ToolCallback> callbacks, String name) {
		return callbacks.stream()
				.filter(tc -> tc.getToolDefinition().name().equals(name))
				.findFirst()
				.orElseThrow();
	}

}