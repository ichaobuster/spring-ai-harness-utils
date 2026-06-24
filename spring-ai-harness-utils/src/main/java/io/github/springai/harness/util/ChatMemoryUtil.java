package io.github.springai.harness.util;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * ChatMemoryUtil
 *
 * @author ichaobuster
 */
public class ChatMemoryUtil {

	public static void replaceChatMemoryMessages(ChatMemory chatMemory, String conversationId, List<Message> messages) {
		if (chatMemory == null) {
			return;
		}
		chatMemory.clear(conversationId);
		chatMemory.add(conversationId, messages);
	}

	public static void replaceChatMemoryMessages(ChatMemory chatMemory, Map<String, Object> context, List<Message> messages) {
		if (chatMemory == null || context == null || context.get(ChatMemory.CONVERSATION_ID) == null) {
			return;
		}
		String conversationId = context.get(ChatMemory.CONVERSATION_ID).toString();
		if (!StringUtils.hasText(conversationId)) {
			return;
		}

		replaceChatMemoryMessages(chatMemory, conversationId, messages);
	}

}
