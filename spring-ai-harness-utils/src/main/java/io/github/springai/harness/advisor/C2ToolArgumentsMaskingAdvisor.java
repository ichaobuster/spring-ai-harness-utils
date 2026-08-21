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
import org.springframework.ai.util.json.JsonParser;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Masks C2 sensitive data in JSON string values before tool execution.
 */
public final class C2ToolArgumentsMaskingAdvisor implements BaseAdvisor {

	private static final int DEFAULT_ORDER = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 100;

	private final C2DataMaskingService maskingService;

	private final int order;

	private C2ToolArgumentsMaskingAdvisor(C2DataMaskingService maskingService, int order) {
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
		return maskToolCalls(response);
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		Assert.notNull(request, "request must not be null");
		Assert.notNull(chain, "chain must not be null");

		return Flux.defer(() -> {
			Map<GenerationKey, ToolCallAccumulator> accumulators = new LinkedHashMap<>();
			Map<GenerationKey, Generation> lastGenerations = new LinkedHashMap<>();
			AtomicReference<ChatClientResponse> lastResponse = new AtomicReference<>();
			Flux<ChatClientResponse> stripped = chain.nextStream(request).map(response -> {
				lastResponse.set(response);
				return stripAndCollectToolCalls(response, accumulators, lastGenerations);
			});
			return stripped.concatWith(
					Flux.defer(() -> emitMaskedToolCalls(lastResponse.get(), accumulators, lastGenerations)));
		});
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	private ChatClientResponse maskToolCalls(ChatClientResponse response) {
		if (response == null || response.chatResponse() == null) {
			return response;
		}
		ChatResponse chatResponse = response.chatResponse();
		boolean modified = false;
		List<Generation> generations = new ArrayList<>(chatResponse.getResults().size());
		for (Generation generation : chatResponse.getResults()) {
			AssistantMessage message = generation.getOutput();
			List<AssistantMessage.ToolCall> maskedCalls = maskToolCalls(message.getToolCalls());
			if (maskedCalls != message.getToolCalls()) {
				modified = true;
				generations.add(new Generation(copyMessage(message, maskedCalls), generation.getMetadata()));
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

	private List<AssistantMessage.ToolCall> maskToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
		if (toolCalls == null || toolCalls.isEmpty()) {
			return toolCalls;
		}
		boolean modified = false;
		List<AssistantMessage.ToolCall> result = new ArrayList<>(toolCalls.size());
		for (AssistantMessage.ToolCall toolCall : toolCalls) {
			String maskedArguments = maskArguments(toolCall.arguments());
			if (!Objects.equals(maskedArguments, toolCall.arguments())) {
				modified = true;
				result.add(new AssistantMessage.ToolCall(toolCall.id(), toolCall.type(), toolCall.name(), maskedArguments));
			}
			else {
				result.add(toolCall);
			}
		}
		return modified ? List.copyOf(result) : toolCalls;
	}

	private String maskArguments(String arguments) {
		if (!StringUtils.hasText(arguments)) {
			return arguments;
		}
		Object parsed;
		try {
			parsed = JsonParser.fromJson(arguments, Object.class);
		}
		catch (RuntimeException ex) {
			if (!this.maskingService.detect(arguments).isEmpty()) {
				throw new IllegalStateException("Tool arguments contain C2 data but are not valid JSON");
			}
			return arguments;
		}
		Object masked = maskJsonValue(parsed);
		return Objects.equals(parsed, masked) ? arguments : JsonParser.toJson(masked);
	}

	private Object maskJsonValue(Object value) {
		if (value instanceof String stringValue) {
			return this.maskingService.mask(stringValue);
		}
		if (value instanceof Map<?, ?> mapValue) {
			Map<Object, Object> masked = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
				masked.put(entry.getKey(), maskJsonValue(entry.getValue()));
			}
			return masked;
		}
		if (value instanceof List<?> listValue) {
			return listValue.stream().map(this::maskJsonValue).toList();
		}
		return value;
	}

	private ChatClientResponse stripAndCollectToolCalls(ChatClientResponse response,
	                                                    Map<GenerationKey, ToolCallAccumulator> accumulators, Map<GenerationKey, Generation> lastGenerations) {
		if (response == null || response.chatResponse() == null) {
			return response;
		}
		ChatResponse chatResponse = response.chatResponse();
		boolean modified = false;
		List<Generation> generations = new ArrayList<>(chatResponse.getResults().size());
		for (int i = 0; i < chatResponse.getResults().size(); i++) {
			Generation generation = chatResponse.getResults().get(i);
			GenerationKey generationKey = generationKey(generation, i);
			lastGenerations.put(generationKey, generation);
			AssistantMessage message = generation.getOutput();
			if (message.hasToolCalls()) {
				modified = true;
				accumulators.computeIfAbsent(generationKey, key -> new ToolCallAccumulator()).add(message.getToolCalls());
				generations.add(new Generation(copyMessage(message, List.of()), generation.getMetadata()));
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

	private Flux<ChatClientResponse> emitMaskedToolCalls(ChatClientResponse lastResponse,
	                                                     Map<GenerationKey, ToolCallAccumulator> accumulators, Map<GenerationKey, Generation> lastGenerations) {
		if (lastResponse == null || lastResponse.chatResponse() == null || accumulators.isEmpty()) {
			return Flux.empty();
		}
		ChatResponse chatResponse = lastResponse.chatResponse();
		List<Generation> generations = new ArrayList<>();
		for (Map.Entry<GenerationKey, ToolCallAccumulator> entry : accumulators.entrySet()) {
			Generation source = Objects.requireNonNull(lastGenerations.get(entry.getKey()),
					"stream generation must be available for every tool call accumulator");
			List<AssistantMessage.ToolCall> calls = maskToolCalls(entry.getValue().build());
			AssistantMessage message = AssistantMessage.builder()
					.content("")
					.properties(source.getOutput().getMetadata())
					.toolCalls(calls)
					.build();
			generations.add(new Generation(message, source.getMetadata()));
		}
		ChatClientResponse masked = lastResponse.mutate()
				.chatResponse(ChatResponse.builder().from(chatResponse).generations(generations).build())
				.build();
		return Flux.just(masked);
	}

	private GenerationKey generationKey(Generation generation, int ordinal) {
		Object index = generation.getOutput().getMetadata().get("index");
		if (index == null && generation.getMetadata().containsKey("index")) {
			index = generation.getMetadata().get("index");
		}
		return index == null ? new GenerationKey("ordinal:" + ordinal) : new GenerationKey("index:" + index);
	}

	private AssistantMessage copyMessage(AssistantMessage source, List<AssistantMessage.ToolCall> toolCalls) {
		return AssistantMessage.builder()
				.content(source.getText())
				.properties(source.getMetadata())
				.toolCalls(toolCalls)
				.media(source.getMedia())
				.build();
	}

	private record GenerationKey(String value) {
	}

	private static final class ToolCallAccumulator {

		private final Map<String, MutableToolCall> calls = new LinkedHashMap<>();

		private final Map<Integer, String> ordinalKeys = new LinkedHashMap<>();

		void add(List<AssistantMessage.ToolCall> fragments) {
			for (int i = 0; i < fragments.size(); i++) {
				AssistantMessage.ToolCall fragment = fragments.get(i);
				String key = this.ordinalKeys.get(i);
				if (StringUtils.hasText(fragment.id())) {
					String idKey = "id:" + fragment.id();
					if (key != null && key.startsWith("ordinal:") && !key.equals(idKey)) {
						MutableToolCall ordinalCall = this.calls.remove(key);
						MutableToolCall idCall = this.calls.computeIfAbsent(idKey, ignored -> new MutableToolCall());
						if (ordinalCall != null && ordinalCall != idCall) {
							idCall.merge(ordinalCall);
						}
					}
					key = idKey;
					this.ordinalKeys.put(i, key);
				}
				else if (key == null) {
					key = "ordinal:" + i;
					this.ordinalKeys.put(i, key);
				}
				this.calls.computeIfAbsent(key, ignored -> new MutableToolCall()).add(fragment);
			}
		}

		List<AssistantMessage.ToolCall> build() {
			return this.calls.values().stream().map(MutableToolCall::build).toList();
		}

	}

	private static final class MutableToolCall {

		private String id = "";

		private String type = "function";

		private String name = "";

		private String arguments = "";

		void add(AssistantMessage.ToolCall fragment) {
			if (StringUtils.hasText(fragment.id())) {
				this.id = fragment.id();
			}
			if (StringUtils.hasText(fragment.type())) {
				this.type = fragment.type();
			}
			if (StringUtils.hasText(fragment.name())) {
				this.name = fragment.name();
			}
			if (fragment.arguments() != null) {
				if (fragment.arguments().startsWith(this.arguments)) {
					this.arguments = fragment.arguments();
				}
				else {
					this.arguments += fragment.arguments();
				}
			}
		}

		void merge(MutableToolCall previous) {
			add(previous.build());
		}

		AssistantMessage.ToolCall build() {
			return new AssistantMessage.ToolCall(this.id, this.type, this.name, this.arguments);
		}

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

		public C2ToolArgumentsMaskingAdvisor build() {
			return new C2ToolArgumentsMaskingAdvisor(this.maskingService, this.order);
		}

	}

}
