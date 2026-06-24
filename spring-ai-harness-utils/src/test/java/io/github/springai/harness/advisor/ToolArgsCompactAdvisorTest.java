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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.util.json.JsonParser;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolArgsCompactAdvisorTest {

	@Mock
	private AdvisorChain advisorChain;

	private ToolArgsCompactAdvisor advisor;

	@BeforeEach
	void setUp() {
		advisor = ToolArgsCompactAdvisor.builder()
				.triggerMessages(2)
				.keepRecent(1)
				.maxArgLength(1000)
				.truncationText(ToolArgsCompactAdvisor.DEFAULT_TRUNCATION_TEXT)
				.order(10)
				.chatMemory(mock(ChatMemory.class))
				.build();
	}

	// ==================== Builder & Metadata ====================

	@Nested
	@DisplayName("Builder and Metadata info")
	class BuilderAndMetadataTests {

		@Test
		@DisplayName("Order returns configured value")
		void testOrder() {
			assertEquals(10, advisor.getOrder());
		}

		@Test
		@DisplayName("After method works properly and returns original response")
		void testAfterMethod() {
			ChatClientResponse response = ChatClientResponse.builder().build();
			assertSame(response, advisor.after(response, advisorChain));
		}
	}

	// ==================== Short circuits ====================

	@Nested
	@DisplayName("Short circuit bounds testing")
	class ShortCircuitTests {

		@Test
		@DisplayName("Null instructions returns immediately")
		void testNullInstructions() {
			Prompt prompt = mock(Prompt.class);
			when(prompt.getInstructions()).thenReturn(null);
			ChatClientRequest req = new ChatClientRequest(prompt, Map.of());
			assertSame(req, advisor.before(req, advisorChain));
		}

		@Test
		@DisplayName("No assistant messages skips execution")
		void testNoAssistantMessages() {
			ChatClientRequest req = new ChatClientRequest(new Prompt(List.of(new UserMessage("hey"))), Map.of());
			assertSame(req, advisor.before(req, advisorChain));
		}

		@Test
		@DisplayName("No compactable messages skips execution")
		void testNoCompactableToolMessages() {
			List<Message> msgs = List.of(
					buildAssistantMessage("id1", "not_compactable", Map.of("foo", "bar"))
			);
			ChatClientRequest req = new ChatClientRequest(new Prompt(msgs), Map.of());
			assertSame(req, advisor.before(req, advisorChain));
		}
	}

	// ==================== Count based ====================

	@Nested
	@DisplayName("Count-based trigger scenarios")
	class CountBasedTests {

		@Test
		@DisplayName("When under triggerMessages, stays uncompressed")
		void testUnderTriggerMessages() {
			// triggerMessages is 2, length is 2. (needs > 2 to trigger)
			List<Message> msgs = List.of(
					buildAssistantMessage("id1", "bash", Map.of("foo", "bar")),
					buildAssistantMessage("id2", "file_read", Map.of("foo", "bar"))
			);
			ChatClientRequest req = new ChatClientRequest(new Prompt(msgs), Map.of());
			assertSame(req, advisor.before(req, advisorChain));
		}

		@Test
		@DisplayName("When above triggerMessages, clears older ones")
		void testAboveTriggerMessages() {
			// triggerMessages = 2, keep = 1. Length = 3.
			List<Message> msgs = List.of(
					buildAssistantMessage("id1", "tool1", buildLongArgs(1100)),     // older, clears
					buildAssistantMessage("id2", "tool2", buildLongArgs(1100)),     // older, clears
					buildAssistantMessage("id3", "tool3", buildLongArgs(1100)) // newest, keeps
			);
			ChatClientRequest req = new ChatClientRequest(new Prompt(msgs), Map.of());

			ChatClientRequest res = advisor.before(req, advisorChain);
			assertNotSame(req, res);

			List<Message> updated = res.prompt().getInstructions();

			assertThat(((AssistantMessage) updated.get(0)).getToolCalls().get(0).arguments()).contains(ToolArgsCompactAdvisor.DEFAULT_TRUNCATION_TEXT);
			assertThat(((AssistantMessage) updated.get(1)).getToolCalls().get(0).arguments()).contains(ToolArgsCompactAdvisor.DEFAULT_TRUNCATION_TEXT);
			assertThat(((AssistantMessage) updated.get(2)).getToolCalls().get(0).arguments()).doesNotContain(ToolArgsCompactAdvisor.DEFAULT_TRUNCATION_TEXT);
		}

		@Test
		void testArgLengthLessThenMaxArgLength() {
			// Threshold = 2, keep = 1. Length = 3.
			List<Message> msgs = List.of(
					buildAssistantMessage("id1", "tool1", buildLongArgs(10)),
					buildAssistantMessage("id2", "tool2", buildLongArgs(10)),
					buildAssistantMessage("id3", "tool3", buildLongArgs(10))
			);
			ChatClientRequest req = new ChatClientRequest(new Prompt(msgs), Map.of());

			ChatClientRequest res = advisor.before(req, advisorChain);
			assertSame(req, res);
		}

		@Test
		void testEmptyArgs() {
			AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall("id2", "function", "tool2", "");
			AssistantMessage as2 = AssistantMessage.builder()
					.content("")
					.toolCalls(List.of(toolCall))
					.build();

			// Threshold = 2, keep = 1. Length = 3.
			List<Message> msgs = List.of(
					buildAssistantMessage("id1", "tool1", Map.of()),
					as2,
					buildAssistantMessage("id3", "tool3", buildLongArgs(1100))
			);
			ChatClientRequest req = new ChatClientRequest(new Prompt(msgs), Map.of());

			ChatClientRequest res = advisor.before(req, advisorChain);
			assertSame(req, res);
		}
	}

	// ==================== Utilities ====================

	private AssistantMessage buildAssistantMessage(String id, String name, Map<String, Object> args) {
		AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(id, "function", name, JsonParser.toJson(args));
		return AssistantMessage.builder()
				.content("")
				.toolCalls(List.of(toolCall))
				.build();
	}

	private Map<String, Object> buildLongArgs(int argSize) {
		return Map.of("foo", "b".repeat(argSize));
	}

}