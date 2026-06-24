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
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MicroCompactAdvisorTest {

	@Mock
	private AdvisorChain advisorChain;

	private static final String CLEARED_MSG = MicroCompactAdvisor.CLEARED_MESSAGE;

	private MicroCompactAdvisor advisor;

	@BeforeEach
	void setUp() {
		advisor = MicroCompactAdvisor.builder()
				.compactableToolNames("bash", "file_read")
				.triggerThreshold(2)
				.keepRecent(1)
				.keepResultLength(0)
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
		@DisplayName("Builder populates different types of lists correctly")
		void testBuilderSets() {
			MicroCompactAdvisor ad = MicroCompactAdvisor.builder()
					.addCompactableToolName("custom_tool")
					.compactableToolNames(Set.of("tool1", "tool2")) // overrides
					.compactableToolNames("tool3", "tool4") // overrides again
					.build();
			// Since we can't test internal set easily, we test behavior
			List<Message> msgs = List.of(
					buildToolResponseMessage("id1", "tool3", "data1"),
					buildToolResponseMessage("id2", "tool4", "data2"),
					buildToolResponseMessage("id3", "tool4", "data3")
			);
			ChatClientRequest req = new ChatClientRequest(new Prompt(msgs), Map.of());
			// default trigger = 10, won't trigger. Set to 1:
			MicroCompactAdvisor tAd = MicroCompactAdvisor.builder()
					.compactableToolNames("tool3", "tool4")
					.triggerThreshold(1)
					.keepRecent(1)
					.build();
			ChatClientRequest res = tAd.before(req, advisorChain);
			assertNotSame(req, res);
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
		@DisplayName("No tool messages skips execution")
		void testNoToolMessages() {
			ChatClientRequest req = new ChatClientRequest(new Prompt(List.of(new UserMessage("hey"))), Map.of());
			assertSame(req, advisor.before(req, advisorChain));
		}

		@Test
		@DisplayName("No compactable tools skips execution")
		void testNoCompactableToolMessages() {
			List<Message> msgs = List.of(
					buildToolResponseMessage("id1", "not_compactable", "data")
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
		@DisplayName("When under threshold, stays uncompressed")
		void testUnderThreshold() {
			// Threshold is 2, length is 2. (needs > 2 to trigger)
			List<Message> msgs = List.of(
					buildToolResponseMessage("id1", "bash", "d1"),
					buildToolResponseMessage("id2", "file_read", "d2")
			);
			ChatClientRequest req = new ChatClientRequest(new Prompt(msgs), Map.of());
			assertSame(req, advisor.before(req, advisorChain));
		}

		@Test
		@DisplayName("When above threshold, clears older ones")
		void testAboveThreshold() {
			// Threshold = 2, keep = 1. Length = 3.
			List<Message> msgs = List.of(
					buildToolResponseMessage("id1", "bash", "data111"),     // older, clears
					buildToolResponseMessage("id2", "bash", "data222"),     // older, clears
					buildToolResponseMessage("id3", "file_read", "data333") // newest, keeps
			);
			ChatClientRequest req = new ChatClientRequest(new Prompt(msgs), Map.of());

			ChatClientRequest res = advisor.before(req, advisorChain);
			assertNotSame(req, res);

			List<Message> updated = res.prompt().getInstructions();
			// All ToolResponseMessages are mutated if they contain clearIds
			ToolResponseMessage trm1 = (ToolResponseMessage) updated.get(0);
			ToolResponseMessage trm2 = (ToolResponseMessage) updated.get(1);
			ToolResponseMessage trm3 = (ToolResponseMessage) updated.get(2);

			assertEquals(CLEARED_MSG, trm1.getResponses().get(0).responseData());
			assertEquals(CLEARED_MSG, trm2.getResponses().get(0).responseData());
			assertEquals("data333", trm3.getResponses().get(0).responseData());
		}

		@Test
		void testResultLessThenKeepResultLength() {
			advisor = MicroCompactAdvisor.builder()
					.compactableToolNames("bash", "file_read")
					.triggerThreshold(2)
					.keepRecent(1)
					.keepResultLength(100)
					.order(10)
					.chatMemory(mock(ChatMemory.class))
					.build();
			// Threshold = 2, keep = 1. Length = 3.
			List<Message> msgs = List.of(
					buildToolResponseMessage("id1", "bash", "data111"),
					buildToolResponseMessage("id2", "bash", "data222"),
					buildToolResponseMessage("id3", "file_read", "data333")
			);
			ChatClientRequest req = new ChatClientRequest(new Prompt(msgs), Map.of());

			ChatClientRequest res = advisor.before(req, advisorChain);
			assertSame(req, res);
		}

		@Test
		@DisplayName("Already cleared messages are not cleared again")
		void testAlreadyClearedMessages() {
			MicroCompactAdvisor localAdvisor = MicroCompactAdvisor.builder()
					.compactableToolNames("bash")
					.triggerThreshold(1) // triggers eagerly
					.keepRecent(1)
					.build();

			// Total "bash": id1(cleared), id2(new), id3(new)
			List<Message> msgs = List.of(
					buildToolResponseMessage("id1", "bash", CLEARED_MSG),
					buildToolResponseMessage("id2", "bash", "data2"),
					buildToolResponseMessage("id3", "bash", "data3")
			);
			ChatClientRequest req = new ChatClientRequest(new Prompt(msgs), Map.of());
			ChatClientRequest res = localAdvisor.before(req, advisorChain);

			List<Message> updated = res.prompt().getInstructions();
			ToolResponseMessage trm1 = (ToolResponseMessage) updated.get(0);
			// trm1 wasn't touched further (it's copied though if it shares a msg with others?
			// In our buildToolResponseMessage, each msg is distinct)
			// Wait, actually `anyMatch(r -> clearIds.contains(r.id()))` skips id1 entirely.
			// So trm1 is unchanged reference if it were separated.
			// But since I rebuilt the list it will be identical or unchanged.
			assertEquals(CLEARED_MSG, trm1.getResponses().get(0).responseData());

			ToolResponseMessage trm2 = (ToolResponseMessage) updated.get(1);
			assertEquals(CLEARED_MSG, trm2.getResponses().get(0).responseData()); // cleared

			ToolResponseMessage trm3 = (ToolResponseMessage) updated.get(2);
			assertEquals("data3", trm3.getResponses().get(0).responseData()); // kept
		}
	}

	// ==================== Utilities ====================

	private AssistantMessage buildAssistantMessageWithTimestamp(Object timestampVal) {
		AssistantMessage mockMsg = mock(AssistantMessage.class);
		lenient().when(mockMsg.getMessageType()).thenReturn(MessageType.ASSISTANT);
		Map<String, Object> meta = Map.of("created", timestampVal);
		lenient().when(mockMsg.getMetadata()).thenReturn(meta);
		return mockMsg;
	}

	private ToolResponseMessage buildToolResponseMessage(String id, String name, String data) {
		ToolResponseMessage.ToolResponse tr = new ToolResponseMessage.ToolResponse(id, name, data);
		return ToolResponseMessage.builder()
				.responses(List.of(tr))
				.metadata(Map.of())
				.build();
	}
}
