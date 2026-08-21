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
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.util.json.JsonParser;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class C2ToolArgumentsMaskingAdvisorTest {

	private final C2DataMaskingService service = C2DataMaskingService.builder().build();

	private final C2ToolArgumentsMaskingAdvisor advisor = C2ToolArgumentsMaskingAdvisor.builder(this.service).build();

	@Test
	@SuppressWarnings("unchecked")
	void masksNestedStringArgumentsButPreservesNumbersAndMessagePayload() {
		String arguments = "{\"phone\":\"13800138000\",\"count\":13800138000,"
				+ "\"nested\":{\"emails\":[\"alice@example.com\",\"safe\"]}}";
		AssistantMessage.ToolCall first = new AssistantMessage.ToolCall("call-1", "function", "send", arguments);
		AssistantMessage.ToolCall second = new AssistantMessage.ToolCall("call-2", "function", "safe", "{\"v\":\"ok\"}");
		AssistantMessage message = AssistantMessage.builder()
				.content("tool call")
				.properties(Map.of("source", "model"))
				.toolCalls(List.of(first, second))
				.build();
		ChatClientResponse response = response(message);

		ChatClientResponse result = this.advisor.after(response, mock(AdvisorChain.class));
		AssistantMessage output = result.chatResponse().getResult().getOutput();
		Map<String, Object> parsed = JsonParser.fromJson(output.getToolCalls().get(0).arguments(), Map.class);
		Map<String, Object> nested = (Map<String, Object>) parsed.get("nested");

		assertThat(parsed.get("phone")).isEqualTo("138****8000");
		assertThat(parsed.get("count")).isEqualTo(13800138000L);
		assertThat((List<String>) nested.get("emails")).containsExactly("a****@example.com", "safe");
		assertThat(output.getToolCalls().get(1)).isSameAs(second);
		assertThat(output.getText()).isEqualTo("tool call");
		assertThat(output.getMetadata()).containsEntry("source", "model");
	}

	@Test
	void failsClosedForMalformedSensitiveJson() {
		AssistantMessage.ToolCall call = new AssistantMessage.ToolCall("call-1", "function", "send",
				"{\"phone\":\"13800138000\"");

		assertThatThrownBy(() -> this.advisor.after(response(toolMessage(call)), mock(AdvisorChain.class)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Tool arguments contain C2 data but are not valid JSON")
				.hasNoCause()
				.hasMessageNotContaining("13800138000");
	}

	@Test
	void preservesMalformedJsonWhenItHasNoSensitiveData() {
		AssistantMessage.ToolCall call = new AssistantMessage.ToolCall("call-1", "function", "send", "{not-json");
		ChatClientResponse response = response(toolMessage(call));

		assertThat(this.advisor.after(response, mock(AdvisorChain.class))).isSameAs(response);
	}

	@Test
	void preservesBlankArgumentsAndHandlesEmptyStreams() {
		AssistantMessage.ToolCall call = new AssistantMessage.ToolCall("call-1", "function", "send", "");
		ChatClientResponse response = response(toolMessage(call));
		assertThat(this.advisor.after(response, mock(AdvisorChain.class))).isSameAs(response);

		ChatClientRequest request = request();
		StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
		when(chain.nextStream(request)).thenReturn(Flux.empty(), Flux.just(emptyResponse()));
		assertThat(this.advisor.adviseStream(request, chain).collectList().block()).isEmpty();
		assertThat(this.advisor.adviseStream(request, chain).collectList().block()).hasSize(1);
	}

	@Test
	void aggregatesAndMasksStreamedToolArgumentFragments() {
		ChatClientRequest request = request();
		StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
		AssistantMessage.ToolCall first = new AssistantMessage.ToolCall("", "function", "send",
				"{\"phone\":\"13800");
		AssistantMessage.ToolCall second = new AssistantMessage.ToolCall("call-1", "", "", "138000\"}");
		when(chain.nextStream(request))
				.thenReturn(Flux.just(response(toolMessage(first)), response(toolMessage(second)), emptyResponse()));

		List<ChatClientResponse> results = this.advisor.adviseStream(request, chain).collectList().block();
		List<AssistantMessage.ToolCall> streamedCalls = results.stream()
				.flatMap(result -> result.chatResponse().getResults().stream())
				.flatMap(generation -> generation.getOutput().getToolCalls().stream())
				.toList();

		assertThat(streamedCalls).hasSize(1);
		assertThat(streamedCalls.get(0).id()).isEqualTo("call-1");
		assertThat(streamedCalls.get(0).name()).isEqualTo("send");
		assertThat(streamedCalls.get(0).arguments()).contains("138****8000").doesNotContain("13800138000");
	}

	@Test
	void groupsMultipleStreamedToolCallsByIdWhenOrdinalsAreReused() {
		ChatClientRequest request = request();
		StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
		AssistantMessage.ToolCall first = new AssistantMessage.ToolCall("call-1", "function", "sendPhone",
				"{\"value\":\"13800138000\"}");
		AssistantMessage.ToolCall second = new AssistantMessage.ToolCall("call-2", "function", "sendEmail",
				"{\"value\":\"alice@example.com\"}");
		when(chain.nextStream(request))
				.thenReturn(Flux.just(response(toolMessage(first)), response(toolMessage(second)), emptyResponse()));

		List<AssistantMessage.ToolCall> streamedCalls = this.advisor.adviseStream(request, chain)
				.flatMapIterable(result -> result.chatResponse().getResults())
				.flatMapIterable(generation -> generation.getOutput().getToolCalls())
				.collectList()
				.block();

		assertThat(streamedCalls).extracting(AssistantMessage.ToolCall::id)
				.containsExactly("call-1", "call-2");
		assertThat(streamedCalls).extracting(AssistantMessage.ToolCall::arguments)
				.allMatch(arguments -> !arguments.contains("13800138000") && !arguments.contains("alice@example.com"));
	}

	@Test
	void isolatesInterleavedStreamToolCallsByStableChoiceIndex() {
		ChatClientRequest request = request();
		StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
		AssistantMessage.ToolCall firstStart = new AssistantMessage.ToolCall("call-1", "function", "sendPhone",
				"{\"value\":\"13800");
		AssistantMessage.ToolCall secondStart = new AssistantMessage.ToolCall("call-2", "function", "sendEmail",
				"{\"value\":\"alice");
		AssistantMessage.ToolCall firstEnd = new AssistantMessage.ToolCall("call-1", "", "", "138000\"}");
		AssistantMessage.ToolCall secondEnd = new AssistantMessage.ToolCall("call-2", "", "", "@example.com\"}");
		when(chain.nextStream(request))
				.thenReturn(Flux.just(response(indexedToolMessage(0, firstStart)),
						response(indexedToolMessage(1, secondStart)), response(indexedToolMessage(0, firstEnd)),
						response(indexedToolMessage(1, secondEnd)), emptyResponse()));

		List<ChatClientResponse> results = this.advisor.adviseStream(request, chain).collectList().block();
		List<Generation> emitted = results.get(results.size() - 1).chatResponse().getResults();

		assertThat(emitted).hasSize(2);
		assertThat(emitted.get(0).getOutput().getMetadata()).containsEntry("index", 0);
		assertThat(emitted.get(0).getOutput().getToolCalls()).singleElement()
				.satisfies(call -> assertThat(call.arguments()).contains("138****8000"));
		assertThat(emitted.get(1).getOutput().getMetadata()).containsEntry("index", 1);
		assertThat(emitted.get(1).getOutput().getToolCalls()).singleElement()
				.satisfies(call -> assertThat(call.arguments()).contains("a****@example.com"));
	}

	@Test
	void returnsOriginalForResponsesWithoutToolCalls() {
		ChatClientResponse response = response(new AssistantMessage("safe"));

		assertThat(this.advisor.after(response, mock(AdvisorChain.class))).isSameAs(response);
		assertThat(this.advisor.after(null, mock(AdvisorChain.class))).isNull();
		assertThat(this.advisor.after(ChatClientResponse.builder().build(), mock(AdvisorChain.class))).isNotNull();
	}

	@Test
	void builderSupportsCustomOrder() {
		assertThat(this.advisor.getOrder()).isEqualTo(Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 100);
		assertThat(C2ToolArgumentsMaskingAdvisor.builder(this.service).order(21).build().getOrder()).isEqualTo(21);
	}

	private ChatClientRequest request() {
		return ChatClientRequest.builder()
				.prompt(Prompt.builder().messages(new UserMessage("hello")).build())
				.build();
	}

	private AssistantMessage toolMessage(AssistantMessage.ToolCall call) {
		return AssistantMessage.builder().content("").toolCalls(List.of(call)).build();
	}

	private AssistantMessage indexedToolMessage(int index, AssistantMessage.ToolCall call) {
		return AssistantMessage.builder()
				.content("")
				.properties(Map.of("index", index))
				.toolCalls(List.of(call))
				.build();
	}

	private ChatClientResponse response(AssistantMessage message) {
		return ChatClientResponse.builder()
				.chatResponse(ChatResponse.builder()
						.metadata("trace", "kept")
						.generations(List.of(new Generation(message)))
						.build())
				.context(Map.of("conversation", "c1"))
				.build();
	}

	private ChatClientResponse emptyResponse() {
		return ChatClientResponse.builder()
				.chatResponse(ChatResponse.builder().generations(List.of()).build())
				.build();
	}

}
