package io.github.springai.harness.advisor;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AdvancedMessageChatMemoryAdvisorTest {

	// -------------------------------------------------------------------------
	// Builder validation
	// -------------------------------------------------------------------------

	@Test
	void whenChatMemoryIsNullThenThrow() {
		assertThatThrownBy(() -> AdvancedMessageChatMemoryAdvisor.builder(null).build())
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("chatMemory cannot be null");
	}

	@Test
	void whenSchedulerIsNullThenThrow() {
		ChatMemory chatMemory = MessageWindowChatMemory.builder().build();
		assertThatThrownBy(() -> AdvancedMessageChatMemoryAdvisor.builder(chatMemory).scheduler(null).build())
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("scheduler cannot be null");
	}

	@Test
	void whenBuilderWithDefaultsThenSuccess() {
		ChatMemory chatMemory = MessageWindowChatMemory.builder()
				.chatMemoryRepository(new InMemoryChatMemoryRepository())
				.build();
		AdvancedMessageChatMemoryAdvisor advisor = AdvancedMessageChatMemoryAdvisor.builder(chatMemory).build();
		assertThat(advisor.getOrder()).isEqualTo(Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER);
	}

	@Test
	void whenCustomOrderIsSetThenGetOrderReturnsIt() {
		ChatMemory chatMemory = MessageWindowChatMemory.builder().build();
		AdvancedMessageChatMemoryAdvisor advisor = AdvancedMessageChatMemoryAdvisor.builder(chatMemory)
				.order(42)
				.scheduler(Schedulers.immediate())
				.build();
		assertThat(advisor.getOrder()).isEqualTo(42);
	}

	// -------------------------------------------------------------------------
	// Conversation ID resolution from request context
	// -------------------------------------------------------------------------

	@Test
	void whenConversationIdAbsentFromContextThenThrow() {
		ChatMemory chatMemory = MessageWindowChatMemory.builder().build();
		AdvancedMessageChatMemoryAdvisor advisor = AdvancedMessageChatMemoryAdvisor.builder(chatMemory).build();

		assertThatThrownBy(() -> advisor.getConversationId(Map.of())).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("conversationId cannot be null");
	}

	@Test
	void whenConversationIdPresentInContextThenReturn() {
		ChatMemory chatMemory = MessageWindowChatMemory.builder().build();
		AdvancedMessageChatMemoryAdvisor advisor = AdvancedMessageChatMemoryAdvisor.builder(chatMemory).build();

		String result = advisor.getConversationId(Map.of(ChatMemory.CONVERSATION_ID, "session-42"));

		assertThat(result).isEqualTo("session-42");
	}

	// -------------------------------------------------------------------------
	// before() behavior
	// -------------------------------------------------------------------------

	@Test
	void whenBeforeWithUserMessageThenStoreInMemory() {
		ChatMemory chatMemory = MessageWindowChatMemory.builder()
				.chatMemoryRepository(new InMemoryChatMemoryRepository())
				.build();
		AdvancedMessageChatMemoryAdvisor advisor = AdvancedMessageChatMemoryAdvisor.builder(chatMemory).build();
		Prompt prompt = Prompt.builder().messages(new UserMessage("Hello")).build();
		ChatClientRequest request = ChatClientRequest.builder()
				.prompt(prompt)
				.context(ChatMemory.CONVERSATION_ID, "test-conversation")
				.build();
		AdvisorChain chain = mock(AdvisorChain.class);

		advisor.before(request, chain);

		List<Message> messages = chatMemory.get("test-conversation");
		assertThat(messages).hasSize(1);
		assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
		assertThat(messages.get(0).getText()).isEqualTo("Hello");
	}

	@Test
	void whenBeforeUseStrictThenNotStoreInMemory() {
		ChatMemory chatMemory = MessageWindowChatMemory.builder()
				.chatMemoryRepository(new InMemoryChatMemoryRepository())
				.build();
		AdvancedMessageChatMemoryAdvisor advisor = AdvancedMessageChatMemoryAdvisor.builder(chatMemory).useStrict(true).build();
		Prompt prompt = Prompt.builder().messages(new UserMessage("Hello"), new AssistantMessage("Hi")).build();
		ChatClientRequest request = ChatClientRequest.builder()
				.prompt(prompt)
				.context(ChatMemory.CONVERSATION_ID, "test-conversation")
				.build();
		AdvisorChain chain = mock(AdvisorChain.class);

		advisor.before(request, chain);

		List<Message> messages = chatMemory.get("test-conversation");
		assertThat(messages).hasSize(0);
	}

	@Test
	void whenBeforeWithToolResponseMessageThenStoreInMemory() {
		ChatMemory chatMemory = MessageWindowChatMemory.builder()
				.chatMemoryRepository(new InMemoryChatMemoryRepository())
				.build();
		AdvancedMessageChatMemoryAdvisor advisor = AdvancedMessageChatMemoryAdvisor.builder(chatMemory).build();
		ToolResponseMessage toolResponse = ToolResponseMessage.builder()
				.responses(List.of(new ToolResponseMessage.ToolResponse("weatherTool", "getWeather", "Sunny, 72°F")))
				.build();
		Prompt prompt = Prompt.builder()
				.messages(new UserMessage("What's the weather?"), new AssistantMessage("Let me check..."), toolResponse)
				.build();
		ChatClientRequest request = ChatClientRequest.builder()
				.prompt(prompt)
				.context(ChatMemory.CONVERSATION_ID, "test-conversation")
				.build();
		AdvisorChain chain = mock(AdvisorChain.class);

		advisor.before(request, chain);

		List<Message> messages = chatMemory.get("test-conversation");
		assertThat(messages).hasSize(1);
		assertThat(messages.get(0)).isInstanceOf(ToolResponseMessage.class);
	}

	@Test
	void whenBeforeMovesSystemMessageToFirstPosition() {
		ChatMemory chatMemory = MessageWindowChatMemory.builder()
				.chatMemoryRepository(new InMemoryChatMemoryRepository())
				.build();
		chatMemory.add("test-conversation",
				List.of(new UserMessage("Previous question"), new AssistantMessage("Previous answer")));
		AdvancedMessageChatMemoryAdvisor advisor = AdvancedMessageChatMemoryAdvisor.builder(chatMemory).build();
		Prompt prompt = Prompt.builder()
				.messages(new UserMessage("Hello"), new SystemMessage("You are a helpful assistant"))
				.build();
		ChatClientRequest request = ChatClientRequest.builder()
				.prompt(prompt)
				.context(ChatMemory.CONVERSATION_ID, "test-conversation")
				.build();
		AdvisorChain chain = mock(AdvisorChain.class);

		ChatClientRequest processedRequest = advisor.before(request, chain);

		List<Message> processedMessages = processedRequest.prompt().getInstructions();
		assertThat(processedMessages.get(0)).isInstanceOf(SystemMessage.class);
		assertThat(processedMessages.get(0).getText()).isEqualTo("You are a helpful assistant");
	}

	@Test
	void whenBeforeSystemMessageAlreadyFirstThenKeepOrder() {
		ChatMemory chatMemory = MessageWindowChatMemory.builder()
				.chatMemoryRepository(new InMemoryChatMemoryRepository())
				.build();
		AdvancedMessageChatMemoryAdvisor advisor = AdvancedMessageChatMemoryAdvisor.builder(chatMemory).build();
		Prompt prompt = Prompt.builder()
				.messages(new SystemMessage("You are a helpful assistant"), new UserMessage("Hello"))
				.build();
		ChatClientRequest request = ChatClientRequest.builder()
				.prompt(prompt)
				.context(ChatMemory.CONVERSATION_ID, "test-conversation")
				.build();
		AdvisorChain chain = mock(AdvisorChain.class);

		ChatClientRequest processedRequest = advisor.before(request, chain);

		List<Message> processedMessages = processedRequest.prompt().getInstructions();
		assertThat(processedMessages.get(0)).isInstanceOf(SystemMessage.class);
		assertThat(processedMessages.get(0).getText()).isEqualTo("You are a helpful assistant");
		assertThat(processedMessages.get(1)).isInstanceOf(UserMessage.class);
	}

	@Test
	void afterMethodHandlesNullChatResponse() {
		// Create a chat memory
		ChatMemory chatMemory = MessageWindowChatMemory.builder()
				.chatMemoryRepository(new InMemoryChatMemoryRepository())
				.build();
		// Create advisor with default values
		AdvancedMessageChatMemoryAdvisor advisor = AdvancedMessageChatMemoryAdvisor.builder(chatMemory)
				.build();
		// Create a chatClientResponse with no chat response
		ChatClientResponse response = ChatClientResponse.builder().context(ChatMemory.CONVERSATION_ID, "test-conversation").build();
		AdvisorChain chain = mock(AdvisorChain.class);
		advisor.after(response, chain);
		// Verify that the UserMessage was added to memory
		List<Message> messages = chatMemory.get("test-conversation");
		assertThat(messages).hasSize(0);
	}

	@Test
	void afterMethodHandlesSingleChatResponse() {
		// Create a chat memory
		ChatMemory chatMemory = MessageWindowChatMemory.builder()
				.chatMemoryRepository(new InMemoryChatMemoryRepository())
				.build();
		// Create advisor with default values
		AdvancedMessageChatMemoryAdvisor advisor = AdvancedMessageChatMemoryAdvisor.builder(chatMemory)
				.build();
		// Create a chatClientResponse with no chat response
		ChatClientResponse response = ChatClientResponse.builder().chatResponse(ChatResponse.builder()
						.generations(List.of(new Generation(AssistantMessage.builder().content("ok").build())))
						.build())
				.context(ChatMemory.CONVERSATION_ID, "test-conversation")
				.build();
		AdvisorChain chain = mock(AdvisorChain.class);
		advisor.after(response, chain);
		// Verify that the UserMessage was added to memory
		List<Message> messages = chatMemory.get("test-conversation");
		assertThat(messages).hasSize(1);
		assertThat(messages.get(0)).isInstanceOf(AssistantMessage.class);
	}

}