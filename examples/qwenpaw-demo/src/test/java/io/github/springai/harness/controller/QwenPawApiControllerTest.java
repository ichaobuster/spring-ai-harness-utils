package io.github.springai.harness.controller;

import io.github.springai.harness.agent.HarnessAgents;
import io.github.springai.harness.config.AgentConfig;
import io.github.springai.harness.workspace.AgentWorkspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.api.OpenAiApi;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QwenPawApiControllerTest {

	private QwenPawApiController controller;

	@Mock
	private AgentWorkspace agentWorkspace;

	@Mock
	private HarnessAgents harnessAgents;

	@BeforeEach
	void setUp() {
		controller = new QwenPawApiController(agentWorkspace, harnessAgents);
	}

	@Test
	void chat_WithUserTextMessage() {
		// Arrange
		String userId = "user-123";
		String sessionId = "session-456";
		AgentConfig mockConfig = mock(AgentConfig.class);
		when(agentWorkspace.loadAgentConfig(userId)).thenReturn(mockConfig);

		OpenAiApi.ChatCompletionMessage userMsg = mock(OpenAiApi.ChatCompletionMessage.class);
		when(userMsg.role()).thenReturn(OpenAiApi.ChatCompletionMessage.Role.USER);
		when(userMsg.rawContent()).thenReturn("Hello Agent");

		QwenPawApiController.AgentRequest request = new QwenPawApiController.AgentRequest(
				List.of(userMsg),
				sessionId,
				userId,
				"console"
		);

		// Mock the streaming responses
		ChatClientResponse response1 = mock(ChatClientResponse.class);
		ChatResponse chatResponse1 = mock(ChatResponse.class);
		Generation gen1 = mock(Generation.class);
		AssistantMessage assistantMsg1 = mock(AssistantMessage.class);
		when(response1.chatResponse()).thenReturn(chatResponse1);
		when(chatResponse1.getResult()).thenReturn(gen1);
		when(gen1.getOutput()).thenReturn(assistantMsg1);
		when(assistantMsg1.getText()).thenReturn("Hi ");

		ChatClientResponse response2 = mock(ChatClientResponse.class);
		ChatResponse chatResponse2 = mock(ChatResponse.class);
		Generation gen2 = mock(Generation.class);
		AssistantMessage assistantMsg2 = mock(AssistantMessage.class);
		ChatResponseMetadata metadata2 = mock(ChatResponseMetadata.class);
		Usage usage2 = mock(Usage.class);
		when(response2.chatResponse()).thenReturn(chatResponse2);
		when(chatResponse2.getResult()).thenReturn(gen2);
		when(gen2.getOutput()).thenReturn(assistantMsg2);
		when(assistantMsg2.getText()).thenReturn("there!");
		when(chatResponse2.getMetadata()).thenReturn(metadata2);
		when(metadata2.getUsage()).thenReturn(usage2);

		when(harnessAgents.chat(eq(mockConfig), eq(sessionId), eq("Hello Agent"), any(List.class), any()))
				.thenReturn(Flux.just(response1, response2));

		// Act
		Flux<QwenPawApiController.AgentStreamingResponse> responseFlux = controller.chat(request);

		// Assert
		StepVerifier.create(responseFlux)
				.assertNext(res -> {
					assertThat(res.sequenceNumber()).isEqualTo(0L);
					assertThat(res.status()).isEqualTo("created");
					assertThat(res.content().get(0).text()).isEqualTo("Hi ");
					assertThat(res.sessionId()).isEqualTo(sessionId);
					assertThat(res.usage()).isNull();
				})
				.assertNext(res -> {
					assertThat(res.sequenceNumber()).isEqualTo(1L);
					assertThat(res.status()).isEqualTo("completed");
					assertThat(res.content().get(0).text()).isEqualTo("there!");
					assertThat(res.sessionId()).isEqualTo(sessionId);
					assertThat(res.usage()).isNotNull();
				})
				.verifyComplete();
	}

	@Test
	void chat_WithUserListMessage() {
		// Arrange
		String userId = "user-123";
		String sessionId = "session-456";
		AgentConfig mockConfig = mock(AgentConfig.class);
		when(agentWorkspace.loadAgentConfig(userId)).thenReturn(mockConfig);

		OpenAiApi.ChatCompletionMessage userMsg = mock(OpenAiApi.ChatCompletionMessage.class);
		when(userMsg.role()).thenReturn(OpenAiApi.ChatCompletionMessage.Role.USER);
		
		// List content: e.g. [{type: "text", text: "Hello list content"}]
		List<Map<String, Object>> listContent = List.of(
				Map.of("type", "text", "text", "Hello "),
				Map.of("type", "text", "text", "list content"),
				Map.of("type", "image", "url", "http://image.url") // should be filtered out
		);
		when(userMsg.rawContent()).thenReturn(listContent);

		QwenPawApiController.AgentRequest request = new QwenPawApiController.AgentRequest(
				List.of(userMsg),
				sessionId,
				userId,
				"console"
		);

		ChatClientResponse response = mock(ChatClientResponse.class);
		when(harnessAgents.chat(eq(mockConfig), eq(sessionId), eq("Hello list content"), any(List.class), any()))
				.thenReturn(Flux.just(response));

		// Act
		Flux<QwenPawApiController.AgentStreamingResponse> responseFlux = controller.chat(request);

		// Assert
		StepVerifier.create(responseFlux)
				.assertNext(res -> {
					assertThat(res.sequenceNumber()).isEqualTo(0L);
					assertThat(res.status()).isEqualTo("created");
				})
				.verifyComplete();
	}

	@Test
	void chat_WithNoUserMessage() {
		// Arrange
		String userId = "user-123";
		String sessionId = "session-456";
		AgentConfig mockConfig = mock(AgentConfig.class);
		when(agentWorkspace.loadAgentConfig(userId)).thenReturn(mockConfig);

		OpenAiApi.ChatCompletionMessage assistantMsg = mock(OpenAiApi.ChatCompletionMessage.class);
		when(assistantMsg.role()).thenReturn(OpenAiApi.ChatCompletionMessage.Role.ASSISTANT);

		QwenPawApiController.AgentRequest request = new QwenPawApiController.AgentRequest(
				List.of(assistantMsg),
				sessionId,
				userId,
				"console"
		);

		ChatClientResponse response = mock(ChatClientResponse.class);
		when(harnessAgents.chat(eq(mockConfig), eq(sessionId), eq(null), any(List.class), any()))
				.thenReturn(Flux.just(response));

		// Act
		Flux<QwenPawApiController.AgentStreamingResponse> responseFlux = controller.chat(request);

		// Assert
		StepVerifier.create(responseFlux)
				.assertNext(res -> {
					assertThat(res.sequenceNumber()).isEqualTo(0L);
					assertThat(res.status()).isEqualTo("created");
				})
				.verifyComplete();
	}
}
