package io.github.springai.harness.advisor;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.moderation.ModerationModel;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * Moderates the most recent user messages before invoking a chat model.
 */
public final class InputModerationAdvisor implements BaseAdvisor {

	private static final int DEFAULT_ORDER = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 10;

	private final ModerationAdvisorSupport support;

	private final int order;

	private InputModerationAdvisor(ModerationModel moderationModel, int maxModerationCharacters,
			ObservationRegistry observationRegistry, int order) {
		this.support = new ModerationAdvisorSupport(moderationModel, maxModerationCharacters, observationRegistry);
		this.order = order;
	}

	public static Builder builder(ModerationModel moderationModel) {
		return new Builder(moderationModel);
	}

	@Override
	public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
		Observation observation = this.support.currentObservation();
		if (isRejected(request, observation)) {
			throw new ModerationViolationException(ModerationViolationException.Stage.INPUT);
		}
		return request;
	}

	@Override
	public ChatClientResponse after(ChatClientResponse response, AdvisorChain advisorChain) {
		return response;
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		Assert.notNull(request, "request must not be null");
		Assert.notNull(chain, "chain must not be null");
		return Flux.defer(() -> {
			Observation observation = this.support.currentObservation();
			return Mono.fromCallable(() -> isRejected(request, observation))
					.subscribeOn(getScheduler())
					.flatMapMany(rejected -> rejected
							? Flux.just(ModerationResponseFactory.stopped(null, request.context(),
									ModerationViolationException.Stage.INPUT))
							: chain.nextStream(request));
		});
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	private boolean isRejected(ChatClientRequest request, Observation observation) {
		return this.support.isFlagged(collectUserText(request), ModerationViolationException.Stage.INPUT, observation);
	}

	private String collectUserText(ChatClientRequest request) {
		if (request == null || request.prompt() == null || request.prompt().getInstructions() == null) {
			return "";
		}
		List<Message> messages = request.prompt().getInstructions();
		List<String> selected = new ArrayList<>();
		int remaining = this.support.maxModerationCharacters();
		for (int i = messages.size() - 1; i >= 0 && remaining > 0; i--) {
			Message message = messages.get(i);
			if (!(message instanceof UserMessage) || !StringUtils.hasText(message.getText())) {
				continue;
			}
			int separatorLength = selected.isEmpty() ? 0 : 1;
			int available = remaining - separatorLength;
			if (available <= 0) {
				break;
			}
			String text = message.getText();
			String selectedText = text.length() <= available ? text : suffixWithin(text, available);
			if (selectedText.isEmpty()) {
				break;
			}
			selected.add(0, selectedText);
			remaining -= selectedText.length() + separatorLength;
			if (selectedText.length() < text.length()) {
				break;
			}
		}
		return String.join("\n", selected);
	}

	private String suffixWithin(String text, int available) {
		int start = text.length() - available;
		if (start > 0 && start < text.length() && Character.isLowSurrogate(text.charAt(start))
				&& Character.isHighSurrogate(text.charAt(start - 1))) {
			start++;
		}
		return text.substring(start);
	}

	public static final class Builder {

		private final ModerationModel moderationModel;

		private int maxModerationCharacters = ModerationAdvisorSupport.DEFAULT_MAX_MODERATION_CHARACTERS;

		private ObservationRegistry observationRegistry = ObservationRegistry.NOOP;

		private int order = DEFAULT_ORDER;

		private Builder(ModerationModel moderationModel) {
			Assert.notNull(moderationModel, "moderationModel must not be null");
			this.moderationModel = moderationModel;
		}

		public Builder maxModerationCharacters(int maxModerationCharacters) {
			Assert.isTrue(maxModerationCharacters > 0, "maxModerationCharacters must be greater than 0");
			this.maxModerationCharacters = maxModerationCharacters;
			return this;
		}

		public Builder observationRegistry(ObservationRegistry observationRegistry) {
			Assert.notNull(observationRegistry, "observationRegistry must not be null");
			this.observationRegistry = observationRegistry;
			return this;
		}

		public Builder order(int order) {
			this.order = order;
			return this;
		}

		public InputModerationAdvisor build() {
			return new InputModerationAdvisor(this.moderationModel, this.maxModerationCharacters,
					this.observationRegistry, this.order);
		}

	}

}
