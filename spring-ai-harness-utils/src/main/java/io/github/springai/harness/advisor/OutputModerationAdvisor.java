package io.github.springai.harness.advisor;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.moderation.ModerationModel;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Moderates assistant output for call and streaming flows.
 */
public final class OutputModerationAdvisor implements BaseAdvisor {

	public static final int DEFAULT_STREAM_MODERATION_CHUNK_INTERVAL = 100;

	public static final StreamModerationMode DEFAULT_STREAM_MODERATION_MODE = StreamModerationMode.RELEASE_FIRST;

	private static final int DEFAULT_ORDER = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 130;

	private final ModerationAdvisorSupport support;

	private final int streamModerationCharacterInterval;

	private final int streamModerationChunkInterval;

	private final StreamModerationMode streamModerationMode;

	private final int order;

	private OutputModerationAdvisor(ModerationModel moderationModel, int maxModerationCharacters,
			int streamModerationCharacterInterval, int streamModerationChunkInterval,
			StreamModerationMode streamModerationMode, ObservationRegistry observationRegistry, int order) {
		this.support = new ModerationAdvisorSupport(moderationModel, maxModerationCharacters, observationRegistry);
		Assert.isTrue(streamModerationCharacterInterval > 0,
				"streamModerationCharacterInterval must be greater than 0");
		Assert.isTrue(streamModerationCharacterInterval <= this.support.splitCharacters(),
				"streamModerationCharacterInterval must not exceed 90% of maxModerationCharacters");
		Assert.isTrue(streamModerationChunkInterval > 0,
				"streamModerationChunkInterval must be greater than 0");
		Assert.notNull(streamModerationMode, "streamModerationMode must not be null");
		this.streamModerationCharacterInterval = streamModerationCharacterInterval;
		this.streamModerationChunkInterval = streamModerationChunkInterval;
		this.streamModerationMode = streamModerationMode;
		this.order = order;
	}

	public static Builder builder(ModerationModel moderationModel) {
		return new Builder(moderationModel);
	}

	@Override
	public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
		return request;
	}

	@Override
	public ChatClientResponse after(ChatClientResponse response, AdvisorChain advisorChain) {
		if (response == null || response.chatResponse() == null) {
			return response;
		}
		Observation observation = this.support.currentObservation();
		for (Generation generation : response.chatResponse().getResults()) {
			if (this.support.isAnySegmentFlagged(generation.getOutput().getText(),
					ModerationViolationException.Stage.OUTPUT, observation)) {
				throw new ModerationViolationException(ModerationViolationException.Stage.OUTPUT);
			}
		}
		return response;
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		Assert.notNull(request, "request must not be null");
		Assert.notNull(chain, "chain must not be null");
		return this.streamModerationMode == StreamModerationMode.MODERATE_FIRST
				? moderateBeforeRelease(request, chain) : releaseBeforeModeration(request, chain);
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	private Flux<ChatClientResponse> releaseBeforeModeration(ChatClientRequest request, StreamAdvisorChain chain) {
		return Flux.defer(() -> {
			Observation observation = this.support.currentObservation();
			StreamingState state = new StreamingState(this.support.overlapCharacters());
			Flux<ChatClientResponse> processed = chain.nextStream(request).concatMap(response -> {
				boolean hasFinishReason = state.add(response, false);
				boolean auditRequired = hasFinishReason || state.reachedThreshold(
						this.streamModerationCharacterInterval, this.streamModerationChunkInterval);
				if (!auditRequired) {
					return Flux.just(response);
				}
				if (!state.hasPendingText()) {
					state.resetChunksSinceAudit();
					return Flux.just(response);
				}
				if (hasFinishReason) {
					return audit(state, observation)
							.map(result -> result.flagged() ? stopped(response, request, result) : response)
							.flux();
				}
				return Flux.concat(Flux.just(response), audit(state, observation)
						.flatMapMany(result -> result.flagged()
								? Flux.just(stopped(response, request, result)) : Flux.empty()));
			});

			Flux<ChatClientResponse> finalAudit = Flux.defer(() -> {
				if (!state.hasPendingText()) {
					return Flux.empty();
				}
				return audit(state, observation)
						.flatMapMany(result -> result.flagged()
								? Flux.just(stopped(state.lastResponse(), request, result)) : Flux.empty());
			});
			return processed.concatWith(finalAudit).takeUntil(ModerationResponseFactory::isStopped);
		});
	}

	private Flux<ChatClientResponse> moderateBeforeRelease(ChatClientRequest request, StreamAdvisorChain chain) {
		return Flux.defer(() -> {
			Observation observation = this.support.currentObservation();
			StreamingState state = new StreamingState(this.support.overlapCharacters());
			Flux<ChatClientResponse> processed = chain.nextStream(request).concatMap(response -> {
				boolean hasFinishReason = state.add(response, true);
				boolean auditRequired = hasFinishReason || state.reachedThreshold(
						this.streamModerationCharacterInterval, this.streamModerationChunkInterval);
				if (!auditRequired) {
					return Flux.empty();
				}
				if (!state.hasPendingText()) {
					state.resetChunksSinceAudit();
					return Flux.fromIterable(state.drainBufferedResponses());
				}
				return audit(state, observation).flatMapMany(result -> result.flagged()
						? Flux.just(stopped(response, request, result))
						: Flux.fromIterable(state.drainBufferedResponses()));
			});

			Flux<ChatClientResponse> finalAudit = Flux.defer(() -> {
				if (!state.hasBufferedResponses()) {
					return Flux.empty();
				}
				if (!state.hasPendingText()) {
					return Flux.fromIterable(state.drainBufferedResponses());
				}
				return audit(state, observation).flatMapMany(result -> result.flagged()
						? Flux.just(stopped(state.lastResponse(), request, result))
						: Flux.fromIterable(state.drainBufferedResponses()));
			});
			return processed.concatWith(finalAudit).takeUntil(ModerationResponseFactory::isStopped);
		});
	}

	private Mono<AuditResult> audit(StreamingState state, Observation observation) {
		return Mono.fromCallable(() -> state.audit(this.support, observation)).subscribeOn(getScheduler());
	}

	private ChatClientResponse stopped(ChatClientResponse source, ChatClientRequest request, AuditResult result) {
		ModerationResponseFactory.ViolationDetails details = new ModerationResponseFactory.ViolationDetails(
				result.generationIndex(), result.windowStart(), result.windowEnd(), result.safeThrough(),
				result.generationMetadata());
		return ModerationResponseFactory.stopped(source, request.context(),
				ModerationViolationException.Stage.OUTPUT, details);
	}

	/**
	 * Controls whether streaming content is released before or after moderation.
	 */
	public enum StreamModerationMode {

		/**
		 * Releases non-terminal response chunks before moderating the accumulated batch.
		 */
		RELEASE_FIRST,

		/**
		 * Moderates an accumulated batch before releasing its original response chunks.
		 */
		MODERATE_FIRST

	}

	public static final class Builder {

		private final ModerationModel moderationModel;

		private int maxModerationCharacters = ModerationAdvisorSupport.DEFAULT_MAX_MODERATION_CHARACTERS;

		private Integer streamModerationCharacterInterval;

		private int streamModerationChunkInterval = DEFAULT_STREAM_MODERATION_CHUNK_INTERVAL;

		private StreamModerationMode streamModerationMode = DEFAULT_STREAM_MODERATION_MODE;

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

		/**
		 * Sets the number of new UTF-16 characters that triggers streaming moderation.
		 * @param streamModerationCharacterInterval character trigger, at most 90% of
		 * {@code maxModerationCharacters}
		 * @return this builder
		 */
		public Builder streamModerationCharacterInterval(int streamModerationCharacterInterval) {
			Assert.isTrue(streamModerationCharacterInterval > 0,
					"streamModerationCharacterInterval must be greater than 0");
			this.streamModerationCharacterInterval = streamModerationCharacterInterval;
			return this;
		}

		public Builder streamModerationChunkInterval(int streamModerationChunkInterval) {
			Assert.isTrue(streamModerationChunkInterval > 0,
					"streamModerationChunkInterval must be greater than 0");
			this.streamModerationChunkInterval = streamModerationChunkInterval;
			return this;
		}

		/**
		 * Selects whether response batches are released before or after moderation.
		 * @param streamModerationMode streaming release mode
		 * @return this builder
		 */
		public Builder streamModerationMode(StreamModerationMode streamModerationMode) {
			Assert.notNull(streamModerationMode, "streamModerationMode must not be null");
			this.streamModerationMode = streamModerationMode;
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

		public OutputModerationAdvisor build() {
			int characterInterval = this.streamModerationCharacterInterval != null
					? this.streamModerationCharacterInterval
					: ModerationAdvisorSupport.splitCharactersFor(this.maxModerationCharacters);
			return new OutputModerationAdvisor(this.moderationModel, this.maxModerationCharacters, characterInterval,
					this.streamModerationChunkInterval, this.streamModerationMode, this.observationRegistry, this.order);
		}

	}

	private static final class StreamingState {

		private final Map<GenerationKey, GenerationState> generations = new TreeMap<>();

		private final List<ChatClientResponse> bufferedResponses = new ArrayList<>();

		private final int overlapCharacters;

		private int chunksSinceAudit;

		private ChatClientResponse lastResponse;

		private StreamingState(int overlapCharacters) {
			this.overlapCharacters = overlapCharacters;
		}

		boolean add(ChatClientResponse response, boolean bufferResponse) {
			this.lastResponse = response;
			this.chunksSinceAudit++;
			if (bufferResponse) {
				this.bufferedResponses.add(response);
			}
			if (response == null || response.chatResponse() == null) {
				return false;
			}
			boolean hasFinishReason = false;
			List<Generation> results = response.chatResponse().getResults();
			for (int ordinal = 0; ordinal < results.size(); ordinal++) {
				Generation generation = results.get(ordinal);
				GenerationKey generationKey = generationKey(generation, ordinal);
				GenerationState generationState = this.generations.computeIfAbsent(generationKey,
						ignored -> new GenerationState());
				generationState.add(generation);
				if (generation.getMetadata() != null
						&& StringUtils.hasText(generation.getMetadata().getFinishReason())) {
					hasFinishReason = true;
				}
			}
			return hasFinishReason;
		}

		boolean reachedThreshold(int characterInterval, int chunkInterval) {
			return this.chunksSinceAudit >= chunkInterval || this.generations.values()
					.stream()
					.anyMatch(generation -> generation.pendingCharacters() >= characterInterval);
		}

		AuditResult audit(ModerationAdvisorSupport support, Observation observation) {
			Map<GenerationState, String> safeCandidates = new LinkedHashMap<>();
			for (Map.Entry<GenerationKey, GenerationState> entry : this.generations.entrySet()) {
				GenerationState generation = entry.getValue();
				if (!generation.hasPendingText()) {
					continue;
				}
				String candidate = generation.candidate();
				long candidateStart = generation.totalCharacters() - candidate.length();
				ModerationAdvisorSupport.ModerationCheckResult check = support.moderateSegments(candidate,
						ModerationViolationException.Stage.OUTPUT, observation);
				if (check.flagged()) {
					return AuditResult.flagged(entry.getKey().index(), candidateStart + check.windowStart(),
							candidateStart + check.windowEnd(), generation.safeThrough(),
							generation.lastMetadata());
				}
				safeCandidates.put(generation, candidate);
			}
			for (Map.Entry<GenerationState, String> entry : safeCandidates.entrySet()) {
				entry.getKey().markSafe(entry.getValue(), this.overlapCharacters);
			}
			this.chunksSinceAudit = 0;
			return AuditResult.safe();
		}

		boolean hasPendingText() {
			return this.generations.values().stream().anyMatch(GenerationState::hasPendingText);
		}

		void resetChunksSinceAudit() {
			this.chunksSinceAudit = 0;
		}

		boolean hasBufferedResponses() {
			return !this.bufferedResponses.isEmpty();
		}

		List<ChatClientResponse> drainBufferedResponses() {
			List<ChatClientResponse> responses = List.copyOf(this.bufferedResponses);
			this.bufferedResponses.clear();
			return responses;
		}

		ChatClientResponse lastResponse() {
			return this.lastResponse;
		}

		private GenerationKey generationKey(Generation generation, int ordinal) {
			if (generation.getMetadata() == null) {
				return GenerationKey.ordinal(ordinal);
			}
			Object index = generation.getMetadata().get("index");
			if (index instanceof Number number) {
				return GenerationKey.provider(number.intValue());
			}
			if (index instanceof String text) {
				try {
					return GenerationKey.provider(Integer.parseInt(text));
				}
				catch (NumberFormatException ignored) {
					return GenerationKey.ordinal(ordinal);
				}
			}
			return GenerationKey.ordinal(ordinal);
		}

	}

	private record GenerationKey(int index, boolean providerIndex) implements Comparable<GenerationKey> {

		private static GenerationKey provider(int index) {
			return new GenerationKey(index, true);
		}

		private static GenerationKey ordinal(int ordinal) {
			return new GenerationKey(ordinal, false);
		}

		@Override
		public int compareTo(GenerationKey other) {
			int indexComparison = Integer.compare(this.index, other.index);
			return indexComparison != 0 ? indexComparison : Boolean.compare(other.providerIndex, this.providerIndex);
		}

	}

	private static final class GenerationState {

		private final StringBuilder pending = new StringBuilder();

		private String overlap = "";

		private long totalCharacters;

		private long safeThrough;

		private ChatGenerationMetadata lastMetadata;

		void add(Generation generation) {
			this.lastMetadata = generation.getMetadata();
			String text = generation.getOutput().getText();
			if (text != null && !text.isEmpty()) {
				this.pending.append(text);
				this.totalCharacters += text.length();
			}
		}

		String candidate() {
			return this.overlap + this.pending;
		}

		void markSafe(String candidate, int overlapCharacters) {
			this.safeThrough = this.totalCharacters;
			if (overlapCharacters <= 0) {
				this.overlap = "";
			}
			else {
				int start = Math.max(0, candidate.length() - overlapCharacters);
				if (start > 0 && start < candidate.length() && Character.isLowSurrogate(candidate.charAt(start))
						&& Character.isHighSurrogate(candidate.charAt(start - 1))) {
					start++;
				}
				this.overlap = candidate.substring(start);
			}
			this.pending.setLength(0);
		}

		boolean hasPendingText() {
			return !this.pending.isEmpty();
		}

		int pendingCharacters() {
			return this.pending.length();
		}

		long totalCharacters() {
			return this.totalCharacters;
		}

		long safeThrough() {
			return this.safeThrough;
		}

		ChatGenerationMetadata lastMetadata() {
			return this.lastMetadata;
		}

	}

	private record AuditResult(boolean flagged, int generationIndex, long windowStart, long windowEnd,
			long safeThrough, ChatGenerationMetadata generationMetadata) {

		private static AuditResult safe() {
			return new AuditResult(false, -1, -1, -1, -1, null);
		}

		private static AuditResult flagged(int generationIndex, long windowStart, long windowEnd,
				long safeThrough, ChatGenerationMetadata generationMetadata) {
			return new AuditResult(true, generationIndex, windowStart, windowEnd, safeThrough,
					generationMetadata);
		}

	}

}
