package io.github.springai.harness.advisor;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.moderation.ModerationModel;
import org.springframework.ai.moderation.ModerationPrompt;
import org.springframework.ai.moderation.ModerationResponse;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static io.github.springai.harness.advisor.ModerationTestResponses.invalidResponse;
import static io.github.springai.harness.advisor.ModerationTestResponses.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutputModerationAdvisorTest {

	private static final AdvisorChain NOOP_CHAIN = new AdvisorChain() {
	};

	@Test
	void safeNonStreamingResponsePassesThroughAndChecksEveryGeneration() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(false));
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model).build();
		ChatClientResponse response = chatResponse(List.of(chatGeneration("one", null), chatGeneration("two", null)));

		assertThat(advisor.after(response, NOOP_CHAIN)).isSameAs(response);
		assertThat(model.inputs()).containsExactly("one", "two");
	}

	@Test
	void beforeAndEmptyNonStreamingResponsesPassThrough() {
		AtomicInteger calls = new AtomicInteger();
		ModerationModel model = prompt -> {
			calls.incrementAndGet();
			return response(false);
		};
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model).build();
		ChatClientRequest request = request();
		ChatClientResponse emptyOutput = chatResponse("", null);

		assertThat(advisor.before(request, NOOP_CHAIN)).isSameAs(request);
		assertThat(advisor.after(null, NOOP_CHAIN)).isNull();
		assertThat(advisor.after(emptyOutput, NOOP_CHAIN)).isSameAs(emptyOutput);
		assertThat(calls).hasValue(0);
	}

	@Test
	void splitsLongNonStreamingOutputIntoOverlappingBoundedWindows() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(false));
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model).build();
		String text = "x".repeat(10_001);

		advisor.after(chatResponse(List.of(chatGeneration(text, null))), NOOP_CHAIN);

		assertThat(model.inputs()).extracting(String::length).containsExactly(5_000, 5_000, 1_001);
		assertThat(model.inputs().get(0).substring(4_500)).isEqualTo(model.inputs().get(1).substring(0, 500));
		assertThat(model.inputs().get(1).substring(4_500)).isEqualTo(model.inputs().get(2).substring(0, 500));
	}

	@Test
	void throwsWhenAnyNonStreamingSegmentIsFlagged() {
		AtomicInteger calls = new AtomicInteger();
		RecordingModerationModel model = new RecordingModerationModel(
				text -> response(calls.incrementAndGet() == 2));
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model).maxModerationCharacters(10).build();

		assertThatThrownBy(() -> advisor.after(chatResponse(List.of(chatGeneration("x".repeat(20), null))),
				NOOP_CHAIN))
				.isInstanceOfSatisfying(ModerationViolationException.class,
						exception -> assertThat(exception.getStage()).isEqualTo(ModerationViolationException.Stage.OUTPUT));
		assertThat(model.inputs()).hasSize(2);
	}

	@Test
	void auditsAfterConfiguredNumberOfChunksAndThenEmitsStopResponse() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(true));
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model)
				.streamModerationChunkInterval(100)
				.build();
		ChatClientRequest request = request();
		AtomicBoolean cancelled = new AtomicBoolean();
		TestStreamAdvisorChain chain = new TestStreamAdvisorChain(
				Flux.range(0, 200).map(index -> chatResponse("x", null)).doOnCancel(() -> cancelled.set(true)));

		List<ChatClientResponse> results = advisor.adviseStream(request, chain).collectList().block();

		assertThat(results).hasSize(101);
		assertThat(results.subList(0, 100)).allMatch(result -> !ModerationResponseFactory.isStopped(result));
		assertThat(ModerationResponseFactory.isStopped(results.get(100))).isTrue();
		assertThat(results.get(100).chatResponse().getResult().getOutput().getText()).isEmpty();
		assertThat(results.get(100).chatResponse().getResult().getMetadata().getFinishReason())
				.isEqualTo("content_filter");
		assertThat(model.inputs()).containsExactly("x".repeat(100));
		assertThat(cancelled).isTrue();
	}

	@Test
	void defaultCharacterIntervalTriggersAtFourThousandFiveHundredCharacters() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(true));
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model).build();
		ChatClientResponse threshold = chatResponse("x".repeat(4_500), null);
		ChatClientResponse shouldNotBeRead = chatResponse("later", null);
		AtomicBoolean cancelled = new AtomicBoolean();
		TestStreamAdvisorChain chain = new TestStreamAdvisorChain(
				Flux.just(threshold, shouldNotBeRead).doOnCancel(() -> cancelled.set(true)));

		List<ChatClientResponse> results = advisor.adviseStream(request(), chain).collectList().block();

		assertThat(results).hasSize(2);
		assertThat(results.get(0)).isSameAs(threshold);
		assertThat(ModerationResponseFactory.isStopped(results.get(1))).isTrue();
		assertThat(results).doesNotContain(shouldNotBeRead);
		assertThat(model.inputs()).containsExactly("x".repeat(4_500));
		assertThat(cancelled).isTrue();
	}

	@Test
	void carriesTenPercentOverlapIntoNextStreamingAudit() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(false));
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model)
				.maxModerationCharacters(100)
				.streamModerationChunkInterval(2)
				.build();
		ChatClientRequest request = request();
		TestStreamAdvisorChain chain = new TestStreamAdvisorChain(Flux.just(chatResponse("a".repeat(50), null),
				chatResponse("a".repeat(50), null), chatResponse("b", null), chatResponse("b", null)));

		advisor.adviseStream(request, chain).collectList().block();

		assertThat(model.inputs()).containsExactly("a".repeat(100), "a".repeat(10) + "bb");
	}

	@Test
	void moderateFirstReleasesSafeBufferedResponsesInOriginalOrder() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(false));
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model)
				.maxModerationCharacters(100)
				.streamModerationCharacterInterval(2)
				.streamModerationMode(OutputModerationAdvisor.StreamModerationMode.MODERATE_FIRST)
				.build();
		ChatClientResponse first = chatResponse("a", null);
		ChatClientResponse second = chatResponse("b", null);
		ChatClientResponse tail = chatResponse("c", null);

		List<ChatClientResponse> results = advisor
				.adviseStream(request(), new TestStreamAdvisorChain(Flux.just(first, second, tail)))
				.collectList()
				.block();

		assertThat(results).containsExactly(first, second, tail);
		assertThat(model.inputs()).containsExactly("ab", "abc");
	}

	@Test
	void moderateFirstDropsUnsafeBatchAndCancelsUpstream() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(true));
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model)
				.streamModerationMode(OutputModerationAdvisor.StreamModerationMode.MODERATE_FIRST)
				.build();
		ChatClientResponse content = chatResponse("unsafe", null);
		ChatClientResponse finish = chatResponse("", "stop");
		ChatClientResponse shouldNotBeRead = chatResponse("later", null);
		AtomicBoolean cancelled = new AtomicBoolean();
		TestStreamAdvisorChain chain = new TestStreamAdvisorChain(
				Flux.just(content, finish, shouldNotBeRead).doOnCancel(() -> cancelled.set(true)));

		List<ChatClientResponse> results = advisor.adviseStream(request(), chain).collectList().block();

		assertThat(results).hasSize(1).allMatch(ModerationResponseFactory::isStopped);
		assertThat(results).doesNotContain(content, finish, shouldNotBeRead);
		assertThat(model.inputs()).containsExactly("unsafe");
		assertThat(cancelled).isTrue();
	}

	@Test
	void moderateFirstPassesSafeFinishAndPreservesResponseObjects() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(false));
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model)
				.streamModerationMode(OutputModerationAdvisor.StreamModerationMode.MODERATE_FIRST)
				.build();
		ChatClientResponse content = chatResponse("safe", null);
		ChatClientResponse finish = chatResponse("", "stop");

		assertThat(advisor.adviseStream(request(), new TestStreamAdvisorChain(Flux.just(content, finish)))
				.collectList()
				.block()).containsExactly(content, finish);
		assertThat(model.inputs()).containsExactly("safe");
	}

	@Test
	void moderateFirstRejectsUnsafeTailWhenStreamCompletesWithoutFinish() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(true));
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model)
				.streamModerationMode(OutputModerationAdvisor.StreamModerationMode.MODERATE_FIRST)
				.build();
		ChatClientResponse unsafe = chatResponse("unsafe-tail", null);

		List<ChatClientResponse> results = advisor
				.adviseStream(request(), new TestStreamAdvisorChain(Flux.just(unsafe)))
				.collectList()
				.block();

		assertThat(results).hasSize(1).allMatch(ModerationResponseFactory::isStopped);
		assertThat(results).doesNotContain(unsafe);
	}

	@Test
	void moderateFirstFlushesTextlessBatchesWithoutCallingModerationModel() {
		AtomicInteger calls = new AtomicInteger();
		ModerationModel model = prompt -> {
			calls.incrementAndGet();
			return response(false);
		};
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model)
				.streamModerationChunkInterval(2)
				.streamModerationMode(OutputModerationAdvisor.StreamModerationMode.MODERATE_FIRST)
				.build();
		ChatClientResponse first = chatResponse("", null);
		ChatClientResponse second = chatResponse("", null);
		ChatClientResponse tail = chatResponse("", null);

		assertThat(advisor.adviseStream(request(), new TestStreamAdvisorChain(Flux.just(first, second, tail)))
				.collectList()
				.block()).containsExactly(first, second, tail);
		assertThat(calls).hasValue(0);
	}

	@Test
	void holdsFinalChunkUntilShortTailIsModerated() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(true));
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model).build();
		ChatClientRequest request = request();
		ChatClientResponse content = chatResponse("unsafe", null);
		ChatClientResponse finish = chatResponse("", "stop");
		TestStreamAdvisorChain chain = new TestStreamAdvisorChain(Flux.just(content, finish));

		List<ChatClientResponse> results = advisor.adviseStream(request, chain).collectList().block();

		assertThat(results).hasSize(2);
		assertThat(results.get(0)).isSameAs(content);
		assertThat(ModerationResponseFactory.isStopped(results.get(1))).isTrue();
		assertThat(results).doesNotContain(finish);
	}

	@Test
	void emitsEmptyFinishChunkWithoutRepeatingModeration() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(false));
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model)
				.streamModerationChunkInterval(1)
				.build();
		ChatClientResponse content = chatResponse("safe", null);
		ChatClientResponse finish = chatResponse("", "stop");
		TestStreamAdvisorChain chain = new TestStreamAdvisorChain(Flux.just(content, finish));

		assertThat(advisor.adviseStream(request(), chain).collectList().block()).containsExactly(content, finish);
		assertThat(model.inputs()).containsExactly("safe");
	}

	@Test
	void auditsSafeTailWhenStreamCompletesWithoutFinishChunk() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(false));
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model).build();
		ChatClientResponse content = chatResponse("safe-tail", null);

		assertThat(advisor.adviseStream(request(), new TestStreamAdvisorChain(Flux.just(content)))
				.collectList()
				.block()).containsExactly(content);
		assertThat(model.inputs()).containsExactly("safe-tail");
	}

	@Test
	void stopsAfterFlaggedTailWhenStreamCompletesWithoutFinishChunk() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(true));
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model).build();
		ChatClientResponse content = chatResponse("unsafe-tail", null);

		List<ChatClientResponse> results = advisor
				.adviseStream(request(), new TestStreamAdvisorChain(Flux.just(content)))
				.collectList()
				.block();

		assertThat(results).hasSize(2);
		assertThat(results.get(0)).isSameAs(content);
		assertThat(ModerationResponseFactory.isStopped(results.get(1))).isTrue();
	}

	@Test
	void safeFinalChunkPreservesResponseMetadataAndContext() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(false));
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model).build();
		ChatClientRequest request = request();
		ChatClientResponse finish = ChatClientResponse.builder()
				.chatResponse(ChatResponse.builder()
						.metadata("trace", "kept")
						.generations(List.of(chatGeneration("safe", "stop")))
						.build())
				.context(Map.of("conversation", "c1"))
				.build();
		TestStreamAdvisorChain chain = new TestStreamAdvisorChain(Flux.just(finish));

		List<ChatClientResponse> results = advisor.adviseStream(request, chain).collectList().block();

		assertThat(results).containsExactly(finish);
		assertThat((Object) results.get(0).chatResponse().getMetadata().get("trace")).isEqualTo("kept");
		assertThat(results.get(0).context()).containsEntry("conversation", "c1");
	}

	@Test
	void streamStateIsIsolatedPerSubscription() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(false));
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model)
				.streamModerationChunkInterval(2)
				.build();
		ChatClientRequest request = request();
		TestStreamAdvisorChain chain = new TestStreamAdvisorChain(
				Flux.just(chatResponse("a", null), chatResponse("b", "stop")));

		assertThat(advisor.adviseStream(request, chain).collectList().block()).hasSize(2);
		assertThat(advisor.adviseStream(request, chain).collectList().block()).hasSize(2);
		assertThat(model.inputs()).containsExactly("ab", "ab");
	}

	@Test
	void accumulatesEachStreamingGenerationIndependently() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(false));
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model)
				.streamModerationChunkInterval(2)
				.build();
		ChatClientRequest request = request();
		TestStreamAdvisorChain chain = new TestStreamAdvisorChain(Flux.just(
				chatResponse(List.of(chatGeneration("safe-", null), chatGeneration("unsafe", null))),
				chatResponse(List.of(chatGeneration("content", "stop"), chatGeneration("content", "stop")))));

		List<ChatClientResponse> results = advisor.adviseStream(request, chain).collectList().block();

		assertThat(results).hasSize(2);
		assertThat(model.inputs()).containsExactlyInAnyOrder("safe-content", "unsafecontent");
		assertThat(results).noneMatch(ModerationResponseFactory::isStopped);
	}

	@Test
	void usesProviderGenerationIndexWhenChoicesAreSparseAndReordered() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(false));
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model).build();
		ChatClientResponse first = chatResponse(List.of(indexedGeneration("A", null, "7"),
				indexedGeneration("B", null, 3)));
		ChatClientResponse finish = chatResponse(List.of(indexedGeneration("C", "stop", "3"),
				indexedGeneration("D", "stop", 7)));

		List<ChatClientResponse> results = advisor
				.adviseStream(request(), new TestStreamAdvisorChain(Flux.just(first, finish)))
				.collectList()
				.block();

		assertThat(results).containsExactly(first, finish);
		assertThat(model.inputs()).containsExactly("BC", "AD");
	}

	@Test
	void keepsProviderIndexSeparateFromMatchingOrdinalFallback() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(false));
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model).build();
		ChatClientResponse first = chatResponse(List.of(indexedGeneration("provider-", null, 1),
				chatGeneration("fallback-", null)));
		ChatClientResponse finish = chatResponse(List.of(indexedGeneration("tail", "stop", 1),
				chatGeneration("tail", "stop")));

		List<ChatClientResponse> results = advisor
				.adviseStream(request(), new TestStreamAdvisorChain(Flux.just(first, finish)))
				.collectList()
				.block();

		assertThat(results).containsExactly(first, finish);
		assertThat(model.inputs()).containsExactly("provider-tail", "fallback-tail");
	}

	@Test
	void auditsTailOfGenerationThatContinuesAfterAnotherGenerationFinishes() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(text.contains("unsafe")));
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model).build();
		ChatClientResponse first = chatResponse(List.of(indexedGeneration("done", null, 0),
				indexedGeneration("prefix-", null, 1)));
		ChatClientResponse partialFinish = chatResponse(List.of(indexedGeneration("", "stop", 0),
				indexedGeneration("safe", null, 1)));
		ChatClientResponse unsafeTail = chatResponse(List.of(indexedGeneration("-unsafe", null, 1)));

		List<ChatClientResponse> results = advisor
				.adviseStream(request(), new TestStreamAdvisorChain(Flux.just(first, partialFinish, unsafeTail)))
				.collectList()
				.block();

		assertThat(results).hasSize(4);
		assertThat(results.subList(0, 3)).containsExactly(first, partialFinish, unsafeTail);
		assertThat(ModerationResponseFactory.isStopped(results.get(3))).isTrue();
		assertThat(model.inputs()).containsExactly("done", "prefix-safe", "prefix-safe-unsafe");
	}

	@Test
	void stopResponseContainsGenerationWindowAndSafeOffsetMetadata() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(text.contains("bad")));
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model)
				.maxModerationCharacters(100)
				.streamModerationCharacterInterval(10)
				.build();
		ChatClientResponse safe = chatResponse(List.of(indexedGeneration("a".repeat(10), null, 7)));
		ChatClientResponse flagged = ChatClientResponse.builder()
				.chatResponse(ChatResponse.builder()
						.metadata("trace", "kept")
						.generations(List.of(indexedGeneration("bad", "stop", 7)))
						.build())
				.context(Map.of("conversation", "c1"))
				.build();

		List<ChatClientResponse> results = advisor
				.adviseStream(request(), new TestStreamAdvisorChain(Flux.just(safe, flagged)))
				.collectList()
				.block();
		ChatClientResponse stopped = results.get(1);
		AssistantMessage output = stopped.chatResponse().getResult().getOutput();

		assertThat(results).hasSize(2);
		assertThat(stopped).isNotSameAs(flagged);
		assertThat(ModerationResponseFactory.isStopped(stopped)).isTrue();
		assertThat(output.getText()).isEmpty();
		assertThat(output.getToolCalls()).isEmpty();
		assertThat(output.getMedia()).isEmpty();
		assertThat(output.getMetadata())
				.containsEntry(ModerationViolationException.GENERATION_INDEX_METADATA_KEY, 7)
				.containsEntry(ModerationViolationException.WINDOW_START_METADATA_KEY, 0L)
				.containsEntry(ModerationViolationException.WINDOW_END_METADATA_KEY, 13L)
				.containsEntry(ModerationViolationException.SAFE_THROUGH_METADATA_KEY, 10L);
		assertThat((Object) stopped.chatResponse().getMetadata().get("trace")).isEqualTo("kept");
		assertThat(stopped.context()).containsEntry("conversation", "c1");
		assertThat((Object) stopped.chatResponse().getResult().getMetadata().get("index")).isEqualTo(7);
	}

	@Test
	void streamingModelFailureFailsOpenAndMarksCurrentObservation() {
		RuntimeException failure = new RuntimeException("moderation unavailable");
		ModerationModel model = prompt -> {
			throw failure;
		};
		ObservationRegistry registry = ObservationRegistry.create();
		List<Throwable> errors = new ArrayList<>();
		registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
			@Override
			public void onError(Observation.Context context) {
				errors.add(context.getError());
			}

			@Override
			public boolean supportsContext(Observation.Context context) {
				return true;
			}
		});
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model)
				.streamModerationMode(OutputModerationAdvisor.StreamModerationMode.MODERATE_FIRST)
				.observationRegistry(registry)
				.build();
		ChatClientResponse content = chatResponse("allowed-on-failure", null);
		Observation observation = Observation.start("test", registry);

		List<ChatClientResponse> results;
		try (Observation.Scope scope = observation.openScope()) {
			results = advisor.adviseStream(request(), new TestStreamAdvisorChain(Flux.just(content)))
					.collectList()
					.block();
		}
		finally {
			observation.stop();
		}

		assertThat(results).containsExactly(content);
		assertThat(errors).containsExactly(failure);
	}

	@Test
	void characterSplitterMakesProgressForSingleCharacterLimit() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(false));
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model).maxModerationCharacters(1).build();

		advisor.after(chatResponse("😀", null), NOOP_CHAIN);

		assertThat(model.inputs()).hasSize(2).allMatch(text -> text.length() == 1);
	}

	@Test
	void characterSplitterKeepsSurrogatePairTogetherWhenBudgetAllows() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(false));
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model).maxModerationCharacters(4).build();

		advisor.after(chatResponse("ab😀c", null), NOOP_CHAIN);

		assertThat(model.inputs()).containsExactly("ab😀", "😀c");
	}

	@Test
	void characterSplitterAdjustsWindowEndBeforeSurrogatePair() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(false));
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model).maxModerationCharacters(4).build();

		advisor.after(chatResponse("abc😀x", null), NOOP_CHAIN);

		assertThat(model.inputs()).containsExactly("abc", "c😀x");
	}

	@Test
	void characterSplitterDropsTooSmallOverlapInsteadOfStallingOnSurrogatePair() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(false));
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model).maxModerationCharacters(2).build();

		advisor.after(chatResponse("😀x", null), NOOP_CHAIN);

		assertThat(model.inputs()).containsExactly("😀", "x");
	}

	@Test
	void characterSplitterAdvancesWhenWindowEndMovesBeforeSurrogatePair() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(false));
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model).maxModerationCharacters(2).build();

		advisor.after(chatResponse("a😀", null), NOOP_CHAIN);

		assertThat(model.inputs()).containsExactly("a", "😀");
	}

	@Test
	void streamingOverlapNeverRetainsHalfOfSurrogatePair() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(false));
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model)
				.maxModerationCharacters(4)
				.streamModerationCharacterInterval(3)
				.build();

		advisor.adviseStream(request(), new TestStreamAdvisorChain(
				Flux.just(chatResponse("a😀", null), chatResponse("b", "stop"))))
				.collectList()
				.block();

		assertThat(model.inputs()).containsExactly("a😀", "b");
	}

	@Test
	void streamingWithSingleCharacterMaximumMakesProgressWithoutOverlap() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(false));
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model)
				.maxModerationCharacters(1)
				.build();

		assertThat(advisor.adviseStream(request(), new TestStreamAdvisorChain(Flux.just(chatResponse("a", null))))
				.collectList()
				.block()).hasSize(1);
		assertThat(model.inputs()).containsExactly("a");
	}

	@Test
	void invalidModerationResponseFailsOpen() {
		RecordingModerationModel model = new RecordingModerationModel(text -> invalidResponse());
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model).build();
		ChatClientResponse response = chatResponse("safe", null);

		assertThat(advisor.after(response, NOOP_CHAIN)).isSameAs(response);
	}

	@Test
	void nullModerationResponseFailsOpen() {
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(prompt -> null).build();
		ChatClientResponse response = chatResponse("safe", null);

		assertThat(advisor.after(response, NOOP_CHAIN)).isSameAs(response);
	}

	@Test
	void builderValidatesConfigurationAndSupportsCustomOrder() {
		ModerationModel model = prompt -> response(false);
		OutputModerationAdvisor advisor = OutputModerationAdvisor.builder(model)
				.observationRegistry(ObservationRegistry.NOOP)
				.order(43)
				.build();

		assertThat(advisor.getOrder()).isEqualTo(43);
		assertThat(OutputModerationAdvisor.builder(model).build().getOrder())
				.isEqualTo(Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 130);
		assertThatThrownBy(() -> OutputModerationAdvisor.builder(model).maxModerationCharacters(0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> OutputModerationAdvisor.builder(model).streamModerationChunkInterval(0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> OutputModerationAdvisor.builder(model).streamModerationCharacterInterval(0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> OutputModerationAdvisor.builder(model)
				.maxModerationCharacters(100)
				.streamModerationCharacterInterval(91)
				.build()).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> OutputModerationAdvisor.builder(model).streamModerationMode(null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> OutputModerationAdvisor.builder(model).observationRegistry(null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private ChatClientRequest request() {
		return ChatClientRequest.builder()
				.prompt(Prompt.builder().messages(new UserMessage("hello")).build())
				.build();
	}

	private ChatClientResponse chatResponse(String text, String finishReason) {
		return chatResponse(List.of(chatGeneration(text, finishReason)));
	}

	private ChatClientResponse chatResponse(List<Generation> generations) {
		return ChatClientResponse.builder()
				.chatResponse(ChatResponse.builder().generations(generations).build())
				.build();
	}

	private Generation chatGeneration(String text, String finishReason) {
		ChatGenerationMetadata metadata = ChatGenerationMetadata.builder().finishReason(finishReason).build();
		return new Generation(new AssistantMessage(text), metadata);
	}

	private Generation indexedGeneration(String text, String finishReason, Object index) {
		ChatGenerationMetadata metadata = ChatGenerationMetadata.builder()
				.finishReason(finishReason)
				.metadata("index", index)
				.build();
		return new Generation(new AssistantMessage(text), metadata);
	}

	private static final class RecordingModerationModel implements ModerationModel {

		private final Function<String, ModerationResponse> responder;

		private final List<String> inputs = new ArrayList<>();

		private RecordingModerationModel(Function<String, ModerationResponse> responder) {
			this.responder = responder;
		}

		@Override
		public ModerationResponse call(ModerationPrompt prompt) {
			String text = prompt.getInstructions().getText();
			this.inputs.add(text);
			return this.responder.apply(text);
		}

		List<String> inputs() {
			return this.inputs;
		}

	}

	private static final class TestStreamAdvisorChain implements StreamAdvisorChain {

		private final Flux<ChatClientResponse> responses;

		private TestStreamAdvisorChain(Flux<ChatClientResponse> responses) {
			this.responses = responses;
		}

		@Override
		public Flux<ChatClientResponse> nextStream(ChatClientRequest request) {
			return this.responses;
		}

		@Override
		public List<StreamAdvisor> getStreamAdvisors() {
			return List.of();
		}

		@Override
		public StreamAdvisorChain copy(StreamAdvisor after) {
			return this;
		}

	}

}
