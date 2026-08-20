package io.github.springai.harness.advisor;

import io.github.springai.harness.masking.C2DataMaskingService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class C2AssistantMessageMaskingAdvisorTest {

	private final C2DataMaskingService service = C2DataMaskingService.builder().build();

	private final C2AssistantMessageMaskingAdvisor advisor = C2AssistantMessageMaskingAdvisor.builder(this.service)
			.build();

	@Test
	void masksEveryGenerationAndPreservesPayloadAndMetadata() {
		AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall("call-1", "function", "lookup", "{}");
		AssistantMessage first = AssistantMessage.builder()
				.content("手机13800138000")
				.properties(Map.of("source", "model"))
				.toolCalls(List.of(toolCall))
				.build();
		AssistantMessage second = new AssistantMessage("邮箱alice@example.com");
		ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder().finishReason("stop").build();
		ChatResponse chatResponse = ChatResponse.builder()
				.metadata("trace", "kept")
				.generations(List.of(new Generation(first, generationMetadata), new Generation(second, generationMetadata)))
				.build();
		ChatClientResponse response = ChatClientResponse.builder()
				.chatResponse(chatResponse)
				.context(Map.of("conversation", "c1"))
				.build();

		ChatClientResponse result = this.advisor.after(response, mock(AdvisorChain.class));

		assertThat(result.chatResponse().getResults()).extracting(generation -> generation.getOutput().getText())
				.containsExactly("手机138****8000", "邮箱a****@example.com");
		assertThat(result.chatResponse().getResults().get(0).getOutput().getToolCalls()).containsExactly(toolCall);
		assertThat(result.chatResponse().getResults().get(0).getOutput().getMetadata()).containsEntry("source", "model");
		assertThat((Object) result.chatResponse().getMetadata().get("trace")).isEqualTo("kept");
		assertThat(result.context()).containsEntry("conversation", "c1");
		assertThat(result.chatResponse().getResults().get(0).getMetadata()).isSameAs(generationMetadata);
	}

	@Test
	void returnsOriginalResponseWhenNothingNeedsMasking() {
		ChatClientResponse response = response("safe response");

		assertThat(this.advisor.after(response, mock(AdvisorChain.class))).isSameAs(response);
		assertThat(this.advisor.after(null, mock(AdvisorChain.class))).isNull();
		assertThat(this.advisor.after(ChatClientResponse.builder().build(), mock(AdvisorChain.class)))
				.isNotNull();
	}

	@Test
	void handlesEmptyAndMetadataOnlyStreams() {
		ChatClientRequest request = request();
		StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
		when(chain.nextStream(request)).thenReturn(Flux.empty(), Flux.just(emptyResponse()));

		assertThat(this.advisor.adviseStream(request, chain).collectList().block()).isEmpty();
		assertThat(this.advisor.adviseStream(request, chain).collectList().block()).hasSize(1);
	}

	@Test
	void masksSensitiveContentSplitAcrossStreamChunks() {
		ChatClientRequest request = request();
		StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
		when(chain.nextStream(request)).thenReturn(Flux.just(response("请联系13800"), response("138000，邮箱ali"),
				response("ce@example.com"), emptyResponse()));

		List<ChatClientResponse> results = this.advisor.adviseStream(request, chain).collectList().block();
		String text = results.stream()
				.flatMap(result -> result.chatResponse().getResults().stream())
				.map(generation -> generation.getOutput().getText())
				.reduce("", String::concat);

		assertThat(text).isEqualTo("请联系138****8000，邮箱a****@example.com");
		assertThat(text).doesNotContain("13800138000", "alice@example.com");
	}

	@Test
	void streamStateIsIsolatedPerSubscription() {
		ChatClientRequest request = request();
		StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
		when(chain.nextStream(request)).thenReturn(Flux.just(response("13800138000")));

		String first = streamedText(this.advisor.adviseStream(request, chain).collectList().block());
		String second = streamedText(this.advisor.adviseStream(request, chain).collectList().block());

		assertThat(first).isEqualTo("138****8000");
		assertThat(second).isEqualTo(first);
	}

	@Test
	void builderSupportsCustomOrder() {
		assertThat(this.advisor.getOrder()).isEqualTo(Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 120);
		assertThat(C2AssistantMessageMaskingAdvisor.builder(this.service).order(42).build().getOrder()).isEqualTo(42);
	}

	private ChatClientRequest request() {
		return ChatClientRequest.builder()
				.prompt(Prompt.builder().messages(new UserMessage("hello")).build())
				.build();
	}

	private ChatClientResponse response(String text) {
		return ChatClientResponse.builder()
				.chatResponse(ChatResponse.builder()
						.generations(List.of(new Generation(new AssistantMessage(text))))
						.build())
				.build();
	}

	private ChatClientResponse emptyResponse() {
		return ChatClientResponse.builder()
				.chatResponse(ChatResponse.builder().generations(List.of()).build())
				.build();
	}

	private String streamedText(List<ChatClientResponse> responses) {
		return responses.stream()
				.flatMap(response -> response.chatResponse().getResults().stream())
				.map(generation -> generation.getOutput().getText())
				.reduce("", String::concat);
	}

}
