package io.github.springai.harness.advisor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.util.json.JsonParser;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class HumanInTheLoopAfterAdvisorTest {

	@Test
	@DisplayName("after() successfully parses HitlRequest and adds toolId when returnDirect and toolId are present")
	void afterWithHitlRequired() {
		HumanInTheLoopAfterAdvisor advisor = new HumanInTheLoopAfterAdvisor();
		AdvisorChain chain = mock(AdvisorChain.class);

		HumanInTheLoopSpec.HitlRequest hitlRequest = new HumanInTheLoopSpec.HitlRequest(true, "foo", Map.of("arg", "val"), null);
		AssistantMessage am = new AssistantMessage(JsonParser.toJson(hitlRequest));

		ChatGenerationMetadata metadata = ChatGenerationMetadata.builder()
				.finishReason("returnDirect")
				.metadata("toolId", "call_123")
				.build();

		Generation generation = new Generation(am, metadata);
		ChatResponse chatResponse = ChatResponse.builder()
				.generations(List.of(generation))
				.build();

		ChatClientResponse response = ChatClientResponse.builder()
				.chatResponse(chatResponse)
				.build();

		ChatClientResponse result = advisor.after(response, chain);
		assertThat(result).isNotNull();
		assertThat(result).isNotSameAs(response);

		ChatResponse finalCr = result.chatResponse();
		AssistantMessage finalAm = finalCr.getResult().getOutput();
		String content = finalAm.getText();

		HumanInTheLoopSpec.HitlRequest resultRequest = JsonParser.fromJson(content, HumanInTheLoopSpec.HitlRequest.class);
		assertThat(resultRequest.isHitlRequired()).isTrue();
		assertThat(resultRequest.getTool()).isEqualTo("foo");
		assertThat(resultRequest.getArgs()).isEqualTo(Map.of("arg", "val"));
		assertThat(resultRequest.getToolId()).isEqualTo("call_123");
	}

	@Test
	@DisplayName("after() returns original response when hitlRequired is false")
	void afterWithHitlNotRequired() {
		HumanInTheLoopAfterAdvisor advisor = new HumanInTheLoopAfterAdvisor();
		AdvisorChain chain = mock(AdvisorChain.class);

		HumanInTheLoopSpec.HitlRequest hitlRequest = new HumanInTheLoopSpec.HitlRequest(false, "foo", Map.of("arg", "val"), null);
		AssistantMessage am = new AssistantMessage(JsonParser.toJson(hitlRequest));

		ChatGenerationMetadata metadata = ChatGenerationMetadata.builder()
				.finishReason("returnDirect")
				.metadata("toolId", "call_123")
				.build();

		Generation generation = new Generation(am, metadata);
		ChatResponse chatResponse = ChatResponse.builder()
				.generations(List.of(generation))
				.build();

		ChatClientResponse response = ChatClientResponse.builder()
				.chatResponse(chatResponse)
				.build();

		ChatClientResponse result = advisor.after(response, chain);
		assertThat(result).isSameAs(response);
	}

	@Test
	@DisplayName("after() returns original response when toolId is missing")
	void afterWithMissingToolId() {
		HumanInTheLoopAfterAdvisor advisor = new HumanInTheLoopAfterAdvisor();
		AdvisorChain chain = mock(AdvisorChain.class);

		HumanInTheLoopSpec.HitlRequest hitlRequest = new HumanInTheLoopSpec.HitlRequest(true, "foo", Map.of("arg", "val"), null);
		AssistantMessage am = new AssistantMessage(JsonParser.toJson(hitlRequest));

		ChatGenerationMetadata metadata = ChatGenerationMetadata.builder()
				.finishReason("returnDirect")
				// toolId is missing
				.build();

		Generation generation = new Generation(am, metadata);
		ChatResponse chatResponse = ChatResponse.builder()
				.generations(List.of(generation))
				.build();

		ChatClientResponse response = ChatClientResponse.builder()
				.chatResponse(chatResponse)
				.build();

		ChatClientResponse result = advisor.after(response, chain);
		assertThat(result).isSameAs(response);
	}

	@Test
	@DisplayName("after() returns original response when finishReason is not returnDirect")
	void afterWithIncorrectFinishReason() {
		HumanInTheLoopAfterAdvisor advisor = new HumanInTheLoopAfterAdvisor();
		AdvisorChain chain = mock(AdvisorChain.class);

		HumanInTheLoopSpec.HitlRequest hitlRequest = new HumanInTheLoopSpec.HitlRequest(true, "foo", Map.of("arg", "val"), null);
		AssistantMessage am = new AssistantMessage(JsonParser.toJson(hitlRequest));

		ChatGenerationMetadata metadata = ChatGenerationMetadata.builder()
				.finishReason("stop")
				.metadata("toolId", "call_123")
				.build();

		Generation generation = new Generation(am, metadata);
		ChatResponse chatResponse = ChatResponse.builder()
				.generations(List.of(generation))
				.build();

		ChatClientResponse response = ChatClientResponse.builder()
				.chatResponse(chatResponse)
				.build();

		ChatClientResponse result = advisor.after(response, chain);
		assertThat(result).isSameAs(response);
	}

	@Test
	@DisplayName("after() returns original response when message content is not JSON")
	void afterWithInvalidJson() {
		HumanInTheLoopAfterAdvisor advisor = new HumanInTheLoopAfterAdvisor();
		AdvisorChain chain = mock(AdvisorChain.class);

		AssistantMessage am = new AssistantMessage("invalid-json");

		ChatGenerationMetadata metadata = ChatGenerationMetadata.builder()
				.finishReason("returnDirect")
				.metadata("toolId", "call_123")
				.build();

		Generation generation = new Generation(am, metadata);
		ChatResponse chatResponse = ChatResponse.builder()
				.generations(List.of(generation))
				.build();

		ChatClientResponse response = ChatClientResponse.builder()
				.chatResponse(chatResponse)
				.build();

		ChatClientResponse result = advisor.after(response, chain);
		assertThat(result).isSameAs(response);
	}

	@Test
	@DisplayName("builder() sets default order")
	void builderDefaultOrder() {
		HumanInTheLoopAfterAdvisor advisor = HumanInTheLoopAfterAdvisor.builder().build();
		assertThat(advisor.getOrder()).isEqualTo(org.springframework.ai.chat.client.advisor.api.BaseAdvisor.HIGHEST_PRECEDENCE + 250);
	}

	@Test
	@DisplayName("builder() sets custom order")
	void builderCustomOrder() {
		HumanInTheLoopAfterAdvisor advisor = HumanInTheLoopAfterAdvisor.builder().order(123).build();
		assertThat(advisor.getOrder()).isEqualTo(123);
	}
}
