package io.github.springai.harness.advisor;

import io.github.springai.harness.masking.C2DataMaskingService;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.util.Assert;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Masks C2 sensitive data in assistant message content for call and stream flows.
 */
public final class C2AssistantMessageMaskingAdvisor implements BaseAdvisor {

	private static final int DEFAULT_ORDER = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 120;

	private final C2DataMaskingService maskingService;

	private final int order;

	private C2AssistantMessageMaskingAdvisor(C2DataMaskingService maskingService, int order) {
		Assert.notNull(maskingService, "maskingService must not be null");
		this.maskingService = maskingService;
		this.order = order;
	}

	public static Builder builder(C2DataMaskingService maskingService) {
		return new Builder(maskingService);
	}

	@Override
	public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
		return request;
	}

	@Override
	public ChatClientResponse after(ChatClientResponse response, AdvisorChain advisorChain) {
		return maskResponse(response);
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		Assert.notNull(request, "request must not be null");
		Assert.notNull(chain, "chain must not be null");

		return Flux.defer(() -> {
			Map<GenerationKey, C2DataMaskingService.StreamingMaskingSession> sessions = new LinkedHashMap<>();
			Map<GenerationKey, Generation> lastGenerations = new LinkedHashMap<>();
			AtomicReference<ChatClientResponse> lastResponse = new AtomicReference<>();

			Flux<ChatClientResponse> masked = chain.nextStream(request).map(response -> {
				lastResponse.set(response);
				return maskStreamChunk(response, sessions, lastGenerations);
			});

			return masked.concatWith(Flux.defer(() -> flushSessions(lastResponse.get(), sessions, lastGenerations)));
		});
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	private ChatClientResponse maskResponse(ChatClientResponse response) {
		if (response == null || response.chatResponse() == null) {
			return response;
		}
		ChatResponse chatResponse = response.chatResponse();
		boolean modified = false;
		List<Generation> generations = new ArrayList<>(chatResponse.getResults().size());
		for (Generation generation : chatResponse.getResults()) {
			AssistantMessage message = generation.getOutput();
			String maskedText = this.maskingService.mask(message.getText());
			if (!Objects.equals(maskedText, message.getText())) {
				modified = true;
				generations.add(new Generation(copyMessage(message, maskedText), generation.getMetadata()));
			}
			else {
				generations.add(generation);
			}
		}
		if (!modified) {
			return response;
		}
		return response.mutate()
				.chatResponse(ChatResponse.builder().from(chatResponse).generations(generations).build())
				.build();
	}

	private ChatClientResponse maskStreamChunk(ChatClientResponse response,
			Map<GenerationKey, C2DataMaskingService.StreamingMaskingSession> sessions,
			Map<GenerationKey, Generation> lastGenerations) {
		if (response == null || response.chatResponse() == null) {
			return response;
		}
		ChatResponse chatResponse = response.chatResponse();
		List<Generation> generations = new ArrayList<>(chatResponse.getResults().size());
		for (int i = 0; i < chatResponse.getResults().size(); i++) {
			Generation generation = chatResponse.getResults().get(i);
			GenerationKey generationKey = generationKey(generation, i);
			lastGenerations.put(generationKey, generation);
			AssistantMessage message = generation.getOutput();
			String text = message.getText();
			if (text == null) {
				generations.add(generation);
				continue;
			}
			C2DataMaskingService.StreamingMaskingSession session = sessions.computeIfAbsent(generationKey,
					key -> this.maskingService.newStreamingSession());
			String safeText = session.accept(text);
			generations.add(new Generation(copyMessage(message, safeText), generation.getMetadata()));
		}
		return response.mutate()
				.chatResponse(ChatResponse.builder().from(chatResponse).generations(generations).build())
				.build();
	}

	private Flux<ChatClientResponse> flushSessions(ChatClientResponse lastResponse,
			Map<GenerationKey, C2DataMaskingService.StreamingMaskingSession> sessions,
			Map<GenerationKey, Generation> lastGenerations) {
		if (lastResponse == null || lastResponse.chatResponse() == null || sessions.isEmpty()) {
			return Flux.empty();
		}
		ChatResponse chatResponse = lastResponse.chatResponse();
		List<Generation> generations = new ArrayList<>();
		for (Map.Entry<GenerationKey, C2DataMaskingService.StreamingMaskingSession> entry : sessions.entrySet()) {
			Generation source = Objects.requireNonNull(lastGenerations.get(entry.getKey()),
					"stream generation must be available for every masking session");
			String remaining = entry.getValue().finish();
			if (!remaining.isEmpty()) {
				generations.add(new Generation(copyMessageWithoutPayload(source.getOutput(), remaining),
						source.getMetadata()));
			}
		}
		if (generations.isEmpty()) {
			return Flux.empty();
		}
		ChatClientResponse flushed = lastResponse.mutate()
				.chatResponse(ChatResponse.builder().from(chatResponse).generations(generations).build())
				.build();
		return Flux.just(flushed);
	}

	private GenerationKey generationKey(Generation generation, int ordinal) {
		Object index = generation.getOutput().getMetadata().get("index");
		if (index == null && generation.getMetadata().containsKey("index")) {
			index = generation.getMetadata().get("index");
		}
		return index == null ? new GenerationKey("ordinal:" + ordinal) : new GenerationKey("index:" + index);
	}

	private AssistantMessage copyMessage(AssistantMessage source, String content) {
		return AssistantMessage.builder()
				.content(content)
				.properties(source.getMetadata())
				.toolCalls(source.getToolCalls())
				.media(source.getMedia())
				.build();
	}

	private AssistantMessage copyMessageWithoutPayload(AssistantMessage source, String content) {
		return AssistantMessage.builder()
				.content(content)
				.properties(source.getMetadata())
				.build();
	}

	private record GenerationKey(String value) {
	}

	public static final class Builder {

		private final C2DataMaskingService maskingService;

		private int order = DEFAULT_ORDER;

		private Builder(C2DataMaskingService maskingService) {
			Assert.notNull(maskingService, "maskingService must not be null");
			this.maskingService = maskingService;
		}

		public Builder order(int order) {
			this.order = order;
			return this;
		}

		public C2AssistantMessageMaskingAdvisor build() {
			return new C2AssistantMessageMaskingAdvisor(this.maskingService, this.order);
		}

	}

}
