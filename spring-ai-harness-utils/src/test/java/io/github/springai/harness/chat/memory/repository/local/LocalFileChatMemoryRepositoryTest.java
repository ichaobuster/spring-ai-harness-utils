package io.github.springai.harness.chat.memory.repository.local;

import io.github.springai.harness.chat.memory.repository.local.LocalFileChatMemoryRepository;
import io.github.springai.harness.storage.LocalFileStorage;
import io.github.springai.harness.storage.StorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeType;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalFileChatMemoryRepositoryTest {
	LocalFileChatMemoryRepository repository;

	StorageProvider storageProvider;

	@TempDir
	Path tempDir;

	@BeforeEach
	void setUp() {
		storageProvider = LocalFileStorage.builder()
				.baseDir(tempDir)
				.build();
		repository = LocalFileChatMemoryRepository.builder(storageProvider)
				.build();
	}

	@Test
	void builder() {
		var repo = LocalFileChatMemoryRepository.builder(storageProvider)
				.build();
		assertThat(repo).isNotNull();
	}

	@Test
	void saveAndRetrieve() {
		String conversationId = "testconv";
		List<Message> messages = List.of(
				SystemMessage.builder()
						.text("You are a helpful assistant")
						.build(),
				UserMessage.builder()
						.text("Hello")
						.media(List.of(Media.builder().data("http://example.com").mimeType(MimeType.valueOf("image/png")).build()))
						.build(),
				AssistantMessage.builder()
						.content("Hi there!")
						.media(List.of(Media.builder().data("http://example.com").mimeType(MimeType.valueOf("image/png")).build()))
						.toolCalls(List.of(new AssistantMessage.ToolCall("id", "function", "test", "{}")))
						.build(),
				ToolResponseMessage.builder()
						.responses(List.of(new ToolResponseMessage.ToolResponse("id", "test", "ok")))
						.build()
		);

		repository.saveAll(conversationId, messages);

		List<Message> retrieved = repository.findByConversationId(conversationId);
		assertThat(retrieved).hasSize(4);
		assertThat(retrieved.get(0).getMessageType().name()).isEqualTo("SYSTEM");
		assertThat(retrieved.get(0).getText()).isEqualTo("You are a helpful assistant");

		assertThat(retrieved.get(1).getMessageType().name()).isEqualTo("USER");
		assertThat(retrieved.get(1).getText()).isEqualTo("Hello");
		assertThat(((UserMessage) retrieved.get(1)).getMedia()).hasSize(1);
		assertThat(((UserMessage) retrieved.get(1)).getMedia().get(0).getData()).isEqualTo("http://example.com");

		assertThat(retrieved.get(2).getMessageType().name()).isEqualTo("ASSISTANT");
		assertThat(retrieved.get(2).getText()).isEqualTo("Hi there!");
		assertThat(((AssistantMessage) retrieved.get(2)).getToolCalls()).hasSize(1);
		assertThat(((AssistantMessage) retrieved.get(2)).getToolCalls().get(0).id()).isEqualTo("id");
		assertThat(((AssistantMessage) retrieved.get(2)).getToolCalls().get(0).type()).isEqualTo("function");
		assertThat(((AssistantMessage) retrieved.get(2)).getToolCalls().get(0).name()).isEqualTo("test");
		assertThat(((AssistantMessage) retrieved.get(2)).getToolCalls().get(0).arguments()).isEqualTo("{}");
		assertThat(((AssistantMessage) retrieved.get(2)).getMedia()).hasSize(1);
		assertThat(((AssistantMessage) retrieved.get(2)).getMedia().get(0).getData()).isEqualTo("http://example.com");

		assertThat(retrieved.get(3).getMessageType().name()).isEqualTo("TOOL");
		assertThat(((ToolResponseMessage) retrieved.get(3)).getResponses()).hasSize(1);
		assertThat(((ToolResponseMessage) retrieved.get(3)).getResponses().get(0).id()).isEqualTo("id");
		assertThat(((ToolResponseMessage) retrieved.get(3)).getResponses().get(0).name()).isEqualTo("test");
		assertThat(((ToolResponseMessage) retrieved.get(3)).getResponses().get(0).responseData()).isEqualTo("ok");

	}

	@Test
	void findConversationIds() {
		repository.saveAll("conv1", List.of(new UserMessage("msg1")));
		repository.saveAll("conv2", List.of(new UserMessage("msg2")));

		List<String> ids = repository.findConversationIds();
		assertThat(ids).contains("conv1", "conv2");
	}

	@Test
	void deleteByConversationId() {
		String conversationId = "todelete";
		repository.saveAll(conversationId, List.of(new UserMessage("msg")));
		assertThat(repository.findConversationIds()).contains(conversationId);

		repository.deleteByConversationId(conversationId);
		assertThat(repository.findConversationIds()).doesNotContain(conversationId);
		assertThat(repository.findByConversationId(conversationId)).isEmpty();
	}
}