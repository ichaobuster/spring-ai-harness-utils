package io.github.springai.harness.util;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ChatMemoryUtilTest {

	@Test
	void replaceChatMemoryMessages() {
		ChatMemory chatMemory = mock(ChatMemory.class);
		doNothing().when(chatMemory).clear(anyString());
		doNothing().when(chatMemory).add(anyString(), anyList());

		ChatMemoryUtil.replaceChatMemoryMessages(chatMemory, "conversationId", List.of(new UserMessage("test")));
	}

	@Test
	void replaceChatMemoryMessages_chatMemoryIsNull() {
		ChatMemoryUtil.replaceChatMemoryMessages(null, "conversationId", List.of(new UserMessage("test")));
		// nothing happened
	}

	@Test
	void replaceChatMemoryMessages_useContext() {
		ChatMemory chatMemory = mock(ChatMemory.class);
		doNothing().when(chatMemory).clear(anyString());
		doNothing().when(chatMemory).add(anyString(), anyList());

		ChatMemoryUtil.replaceChatMemoryMessages(chatMemory, Map.of(ChatMemory.CONVERSATION_ID, "test"), List.of(new UserMessage("test")));
	}

	@Test
	void replaceChatMemoryMessages_useContextAndChatMemoryNull() {
		ChatMemoryUtil.replaceChatMemoryMessages(null, Map.of(ChatMemory.CONVERSATION_ID, "test"), List.of(new UserMessage("test")));
	}

	@Test
	void replaceChatMemoryMessages_useContextAndConvIdNull() {
		ChatMemory chatMemory = mock(ChatMemory.class);
		doNothing().when(chatMemory).clear(anyString());
		doNothing().when(chatMemory).add(anyString(), anyList());

		ChatMemoryUtil.replaceChatMemoryMessages(chatMemory, Map.of(), List.of(new UserMessage("test")));
	}
}