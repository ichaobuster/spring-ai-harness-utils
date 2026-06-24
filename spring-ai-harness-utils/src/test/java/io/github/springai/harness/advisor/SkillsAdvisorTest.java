package io.github.springai.harness.advisor;

import io.github.springai.harness.tool.SkillsTool.Skill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.DefaultChatClient;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SkillsAdvisor}.
 */
@DisplayName("SkillsAdvisor Tests")
@ExtendWith(MockitoExtension.class)
class SkillsAdvisorTest {

	@Mock
	AdvisorChain advisorChain;

	@Mock
	ToolCallback mockToolCallback;

	@TempDir
	Path tempDir;

	ToolDefinition toolDefinition;

	Skill skill;

	private static ChatClientRequest request(Prompt prompt) {
		return ChatClientRequest.builder().prompt(prompt).build();
	}

	@BeforeEach
	public void setUp() {
		toolDefinition = new ToolDefinition() {
			@Override
			public String name() {
				return "myTool";
			}

			@Override
			public String description() {
				return "myTool's description";
			}

			@Override
			public String inputSchema() {
				return "myTool's inputSchema";
			}
		};
		skill = new Skill(tempDir.toString(), Map.of("name", "test-skill", SkillsAdvisor.TOOL_CALLS_FIELD, List.of("myTool", "toolNotExists")), "content");
	}


	@Test
	@DisplayName("Returns request unchanged when no ToolCallingChatOptions")
	void passesThoughWhenNoOptions() {
		SkillsAdvisor advisor = SkillsAdvisor.builder().skills(List.of(skill)).build();

		ChatClientRequest request = request(new Prompt(new UserMessage("hi")));
		ChatClientRequest result = advisor.before(request, advisorChain);

		assertThat(result).isSameAs(request);
	}

	@Test
	void passesThoughAfter() {
		SkillsAdvisor advisor = SkillsAdvisor.builder().skills(List.of(skill)).build();

		ChatClientResponse response = ChatClientResponse.builder().build();
		ChatClientResponse result = advisor.after(response, advisorChain);
		assertThat(result).isSameAs(response);
	}

	@Test
	@DisplayName("Injects tools when active skill has spring ai tools")
	void injectsTools() {
		when(mockToolCallback.getToolDefinition()).thenReturn(toolDefinition);

		SkillsAdvisor advisor = SkillsAdvisor.builder()
				.skills(List.of(skill))
				.toolRegistry(List.of(mockToolCallback))
				.build();

		AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall("id", "type", SkillsAdvisor.SKILLS_TOOL_NAME, "{\"command\":\"test-skill\"}");
		AssistantMessage message = mock(AssistantMessage.class);
		when(message.getToolCalls()).thenReturn(List.of(toolCall));

		Prompt prompt = new Prompt(List.of(message), OpenAiChatOptions.builder().build());
		ChatClientRequest request = request(prompt);

		ChatClientRequest result = advisor.before(request, advisorChain);

		OpenAiChatOptions opts = (OpenAiChatOptions) result.prompt().getOptions();
		assertThat(opts.getToolCallbacks()).contains(mockToolCallback);
		// 包含 Skills + myTool
		assertThat(opts.getToolCallbacks()).hasSize(2);
	}

	@Test
	@DisplayName("Does not duplicate tools")
	void doesNotDuplicateTools() {
		when(mockToolCallback.getToolDefinition()).thenReturn(toolDefinition);

		SkillsAdvisor advisor = SkillsAdvisor.builder()
				.skills(List.of(skill))
				.toolRegistry(List.of(mockToolCallback))
				.build();

		AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall("id", "type", SkillsAdvisor.SKILLS_TOOL_NAME, "{\"command\":\"test-skill\"}");
		AssistantMessage message = mock(AssistantMessage.class);
		when(message.getToolCalls()).thenReturn(List.of(toolCall));

		OpenAiChatOptions options = OpenAiChatOptions.builder()
				.toolCallbacks(mockToolCallback)
				.build();

		Prompt prompt = new Prompt(List.of(message), options);
		ChatClientRequest request = request(prompt);

		ChatClientRequest result = advisor.before(request, advisorChain);

		OpenAiChatOptions opts = (OpenAiChatOptions) result.prompt().getOptions();
		// 包含 Skills + myTool
		assertThat(opts.getToolCallbacks()).hasSize(2);
	}

	@Test
	@DisplayName("Skill does not contain tools")
	void skillDoesNotContainTools() {
		when(mockToolCallback.getToolDefinition()).thenReturn(toolDefinition);

		Skill skillNoTools = new Skill(tempDir.toString(), Map.of("name", "test-skill"), "content");
		SkillsAdvisor advisor = SkillsAdvisor.builder()
				.skills(List.of(skillNoTools))
				.toolRegistry(List.of(mockToolCallback))
				.build();

		AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall("id", "type", SkillsAdvisor.SKILLS_TOOL_NAME, "{\"command\":\"test-skill\"}");
		AssistantMessage message = mock(AssistantMessage.class);
		when(message.getToolCalls()).thenReturn(List.of(toolCall));

		OpenAiChatOptions options = OpenAiChatOptions.builder()
				.build();

		Prompt prompt = new Prompt(List.of(message), options);
		ChatClientRequest request = request(prompt);

		ChatClientRequest result = advisor.before(request, advisorChain);

		OpenAiChatOptions opts = (OpenAiChatOptions) result.prompt().getOptions();
		// 包含 Skills
		assertThat(opts.getToolCallbacks()).hasSize(1);
	}

	@Test
	@DisplayName("test advisorSpecConsumer")
	void advisorSpecConsumer() {
		SkillsAdvisor advisor = SkillsAdvisor.builder()
				.build();

		advisor.advisorSpecConsumer().accept(new DefaultChatClient.DefaultAdvisorSpec());
	}

	@Test
	@DisplayName("Builder")
	void testBuilder() throws IOException {
		when(mockToolCallback.getToolDefinition()).thenReturn(toolDefinition);

		Files.createDirectories(Path.of(".tmp"));
		SkillsAdvisor advisor = SkillsAdvisor.builder()
				.order(100)
				.skills(List.of(skill))
				.toolRegistry(List.of(mockToolCallback))
				.build();

		assertThat(advisor.getOrder()).isEqualTo(100);
	}

}
