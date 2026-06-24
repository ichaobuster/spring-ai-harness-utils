package io.github.springai.harness.advisor;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ClearThinkingAdvisorTest {

	@Test
	void testGetNameAndOrder() {
		ClearThinkingAdvisor advisor = ClearThinkingAdvisor.builder()
				.order(10)
				.build();
		assertThat(advisor.getName()).isEqualTo("ClearThinkingAdvisor");
		assertThat(advisor.getOrder()).isEqualTo(10);
	}

	@Test
	void testNoPromptOrInstructions() {
		ClearThinkingAdvisor advisor = ClearThinkingAdvisor.builder()
				.keepRecent(1)
				.order(0)
				.chatMemory(mock(ChatMemory.class))
				.build();

		// ChatClientRequest with empty prompt/instructions
		ChatClientRequest requestEmpty = ChatClientRequest.builder()
				.prompt(new Prompt(new ArrayList<>()))
				.build();
		ChatClientRequest result1 = advisor.before(requestEmpty, null);
		assertThat(result1).isEqualTo(requestEmpty);

		// Test with null instructions if possible (some Prompts might allow it)
		try {
			ChatClientRequest requestNullInstructions = ChatClientRequest.builder()
					.prompt(new Prompt((List<Message>) null))
					.build();
			ChatClientRequest result2 = advisor.before(requestNullInstructions, null);
			assertThat(result2).isEqualTo(requestNullInstructions);
		} catch (Exception e) {
			// If new Prompt(null) or build() throws, then this case is naturally handled by the framework
		}
	}

	@Test
	void testKeepRecentN() {
		ClearThinkingAdvisor advisor = ClearThinkingAdvisor.builder()
				.keepRecent(2)
				.build();

		List<Message> messages = new ArrayList<>();
		messages.add(new UserMessage("hi"));
		messages.add(new AssistantMessage("Thinking 1 </think> Result 1"));
		messages.add(new AssistantMessage("Thinking 2 </think> Result 2"));
		messages.add(new AssistantMessage("Thinking 3 </think> Result 3"));

		ChatClientRequest request = ChatClientRequest.builder().prompt(new Prompt(messages)).build();

		ChatClientRequest result = advisor.before(request, null);

		List<Message> resultMessages = result.prompt().getInstructions();
		assertThat(resultMessages).hasSize(4);
		assertThat(resultMessages.get(1).getText()).isEqualTo(" Result 1"); // Cleared (not in last 2 candidates)
		assertThat(resultMessages.get(2).getText()).isEqualTo("Thinking 2 </think> Result 2"); // Kept
		assertThat(resultMessages.get(3).getText()).isEqualTo("Thinking 3 </think> Result 3"); // Kept
	}

	@Test
	void testKeepRecentNWithNoTrigger() {
		ClearThinkingAdvisor advisor = ClearThinkingAdvisor.builder()
				.keepRecent(2)
				.build();

		List<Message> messages = new ArrayList<>();
		messages.add(new AssistantMessage("Thinking 1 </think> Result 1"));

		ChatClientRequest request = ChatClientRequest.builder().prompt(new Prompt(messages)).build();
		ChatClientRequest result = advisor.before(request, null);

		assertThat(result.prompt().getInstructions().get(0).getText()).isEqualTo("Thinking 1 </think> Result 1");
	}

	@Test
	void testEmptyContentHandling() {
		ClearThinkingAdvisor advisor = ClearThinkingAdvisor.builder()
				.keepRecent(1)
				.build();

		List<Message> messages = new ArrayList<>();
		// Candidate 1: Thought only, no tool calls -> should be removed
		messages.add(new AssistantMessage("<think>thinking only</think>"));
		// Candidate 2: Thought only, has tool call -> should keep as empty string
		AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall("1", "call", "func", "{}");
		messages.add(AssistantMessage.builder()
				.content("<think>thinking and tool</think>")
				.toolCalls(List.of(toolCall))
				.build());

		// Candidate 3: Thought only, has media -> should keep as empty string
		Media m = Media.builder()
				.mimeType(MimeTypeUtils.IMAGE_PNG)
				.data("data")
				.build();
		messages.add(AssistantMessage.builder()
				.content("<think>thinking and media</think>")
				.media(List.of(m))
				.build());

		// Candidate 4: Target keep
		messages.add(new AssistantMessage("<think>keep me</think> real"));

		ChatClientRequest request = ChatClientRequest.builder().prompt(new Prompt(messages)).build();
		ChatClientRequest result = advisor.before(request, null);

		List<Message> resultMessages = result.prompt().getInstructions();

		assertThat(resultMessages).hasSize(3);
		assertThat(resultMessages.get(0).getText()).isEqualTo("");
		assertThat(((AssistantMessage) resultMessages.get(0)).hasToolCalls()).isTrue();

		assertThat(resultMessages.get(1).getText()).isEqualTo("");
		assertThat(((AssistantMessage) resultMessages.get(1)).getMedia()).isNotEmpty();

		assertThat(resultMessages.get(2).getText()).isEqualTo("<think>keep me</think> real");
	}

	@Test
	void testMultipleThinkTags() {
		ClearThinkingAdvisor advisor = ClearThinkingAdvisor.builder()
				.keepRecent(1)
				.build();

		List<Message> messages = new ArrayList<>();
		messages.add(new AssistantMessage("Part 1 </think> Part 2 </think> Final Result"));
		messages.add(new AssistantMessage("Keep me </think>"));

		ChatClientRequest request = ChatClientRequest.builder().prompt(new Prompt(messages)).build();
		ChatClientRequest result = advisor.before(request, null);

		assertThat(result.prompt().getInstructions().get(0).getText()).isEqualTo(" Final Result");
	}

	@Test
	void testAfterReturnsResponse() {
		ClearThinkingAdvisor advisor = ClearThinkingAdvisor.builder()
				.keepRecent(1)
				.order(0)
				.chatMemory(null)
				.build();
		assertThat(advisor.after(null, null)).isNull();
	}
}
