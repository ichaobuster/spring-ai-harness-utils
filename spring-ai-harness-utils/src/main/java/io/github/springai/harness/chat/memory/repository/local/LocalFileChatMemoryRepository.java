package io.github.springai.harness.chat.memory.repository.local;

import io.github.springai.harness.storage.StorageProvider;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.content.Media;
import org.springframework.util.Assert;
import org.springframework.util.MimeType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * An implementation of {@link ChatMemoryRepository} for local file system
 *
 * @author ichaobuster
 */
@Slf4j
public class LocalFileChatMemoryRepository implements ChatMemoryRepository {

	private static final String SESSION_FILE_PREFIX = "session_";

	private static final String SESSION_FILE_SUFFIX = ".jsonl";

	private final StorageProvider storageProvider;

	private final ObjectMapper objectMapper;

	private LocalFileChatMemoryRepository(StorageProvider storageProvider) {
		Assert.notNull(storageProvider, "storageProvider must not be null");
		this.storageProvider = storageProvider;

		objectMapper = new ObjectMapper();
	}

	private String getConversationFileName(String conversationId) {
		return SESSION_FILE_PREFIX + conversationId + SESSION_FILE_SUFFIX;
	}

	@Override
	public List<String> findConversationIds() {
		List<String> conversationIds = new ArrayList<>();

		try {
			List<StorageProvider.Info> details = storageProvider.listDirectory("");
			for (StorageProvider.Info detail : details) {
				// 匹配模式：session-*.jsonl
				if (!detail.isDirectory() && detail.path().startsWith(SESSION_FILE_PREFIX) && detail.path().endsWith(SESSION_FILE_SUFFIX)) {
					String conversationId = detail.path().substring(SESSION_FILE_PREFIX.length(), detail.path().length() - SESSION_FILE_SUFFIX.length());
					conversationIds.add(conversationId);
				}
			}
		} catch (IOException e) {
			log.error("Failed to find session files: ", e);
		}

		return conversationIds;
	}

	@Override
	public List<Message> findByConversationId(String conversationId) {
		String fileName = getConversationFileName(conversationId);
		if (!storageProvider.exists(fileName)) {
			return new ArrayList<>();
		}
		try {
			return storageProvider.readAllLines(fileName)
					.stream()
					.filter(text -> text != null && !text.isBlank())
					.map(text -> mapMessage(text))
					.collect(Collectors.toList());
		} catch (IOException e) {
			log.error("Failed to read session file: ", e);
			throw new RuntimeException(e);
		}

	}

	@Override
	public void saveAll(String conversationId, List<Message> messages) {
		deleteByConversationId(conversationId);
		String conversationText = messages.stream()
				.map(message -> {
					try {
						return objectMapper.writer()
								.without(SerializationFeature.INDENT_OUTPUT)
								.writeValueAsString(toMessageRecord(message));
					} catch (JsonProcessingException e) {
						log.error("Failed to convert message to json: ", e);
						throw new RuntimeException(e);
					}
				}).collect(Collectors.joining("\n"));
		try {
			storageProvider.writeString(getConversationFileName(conversationId), conversationText);
		} catch (IOException e) {
			log.error("Failed to write session file: ", e);
			throw new RuntimeException(e);
		}

	}

	@Override
	public void deleteByConversationId(String conversationId) {
		String fileName = getConversationFileName(conversationId);
		if (!storageProvider.exists(fileName)) {
			return;
		}
		try {
			storageProvider.delete(getConversationFileName(conversationId));
		} catch (IOException e) {
			log.error("Failed to delete conversation file: ", e);
			throw new RuntimeException(e);
		}
	}

	public Message mapMessage(String messageText) {
		try {
			var msgRecord = this.objectMapper.readValue(messageText, MessageRecord.class);
			return switch (msgRecord.messageType()) {
				case USER -> msgRecord.toUserMessage();
				case ASSISTANT -> msgRecord.toAssistantMessage();
				case SYSTEM -> msgRecord.toSystemMessage();
				case TOOL -> msgRecord.toToolResponseMessage();
			};
		} catch (JsonProcessingException e) {
			log.error("Failed to read message: ", e);
			throw new RuntimeException(e);
		}
	}

	public static Builder builder(StorageProvider storageProvider) {
		return new Builder(storageProvider);
	}

	public final static class Builder {

		private StorageProvider storageProvider;

		/**
		 * Set the storage provider where all chat sessions files are stored.
		 *
		 * @param storageProvider the storage provider
		 * @return this builder
		 */
		private Builder(StorageProvider storageProvider) {
			this.storageProvider = storageProvider;
		}

		public LocalFileChatMemoryRepository build() {
			return new LocalFileChatMemoryRepository(this.storageProvider);
		}
	}

	public MessageRecord toMessageRecord(Message message) {
		List<AssistantMessage.ToolCall> toolCalls = null;
		List<ToolResponseMessage.ToolResponse> responses = null;
		List<MediaRecord> mediaList = null;
		if (message instanceof UserMessage userMessage) {
			mediaList = userMessage.getMedia().stream()
					.filter(media -> media.getData() instanceof String)
					.map(media -> new MediaRecord(media.getId(), media.getName(), (String) media.getData(), media.getMimeType().toString()))
					.collect(Collectors.toList());
		}
		if (message instanceof AssistantMessage assistantMessage) {
			toolCalls = assistantMessage.getToolCalls();
			mediaList = assistantMessage.getMedia().stream()
					.filter(media -> media.getData() instanceof String)
					.map(media -> new MediaRecord(media.getId(), media.getName(), (String) media.getData(), media.getMimeType().toString()))
					.collect(Collectors.toList());
		}
		if (message instanceof ToolResponseMessage toolResponseMessage) {
			responses = toolResponseMessage.getResponses();
		}
		return new MessageRecord(message.getMessageType(), message.getText(), toolCalls, responses, mediaList);
	}

	// TODO media 怎么处理？
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record MessageRecord(@JsonProperty("messageType") MessageType messageType,
								@JsonProperty("text") String text,
								@JsonProperty("toolCalls") List<AssistantMessage.ToolCall> toolCalls,
								@JsonProperty("responses") List<ToolResponseMessage.ToolResponse> responses,
								@JsonProperty("media") List<MediaRecord> media) {


		public SystemMessage toSystemMessage() {
			return SystemMessage.builder().text(text).build();
		}

		public UserMessage toUserMessage() {
			return UserMessage.builder()
					.text(text)
					.media(media.stream().map(mediaRecord -> Media.builder()
									.id(mediaRecord.id())
									.name(mediaRecord.name())
									.data(mediaRecord.data())
									.mimeType(MimeType.valueOf(mediaRecord.mimeType))
									.build())
							.collect(Collectors.toList())
					)
					.build();
		}

		public AssistantMessage toAssistantMessage() {
			return AssistantMessage.builder()
					.content(text)
					.toolCalls(toolCalls)
					.media(media.stream().map(mediaRecord -> Media.builder()
									.id(mediaRecord.id())
									.name(mediaRecord.name())
									.data(mediaRecord.data())
									.mimeType(MimeType.valueOf(mediaRecord.mimeType))
									.build())
							.collect(Collectors.toList())
					)
					.build();
		}

		public ToolResponseMessage toToolResponseMessage() {
			return ToolResponseMessage.builder()
					.responses(responses)
					.build();
		}

	}

	public record MediaRecord(@JsonProperty("id") String id,
							  @JsonProperty("name") String name,
							  @JsonProperty("data") String data,
							  @JsonProperty("mimeType") String mimeType) {
	}

}
