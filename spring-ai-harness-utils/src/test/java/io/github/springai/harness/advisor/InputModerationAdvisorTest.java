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
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.moderation.ModerationModel;
import org.springframework.ai.moderation.ModerationPrompt;
import org.springframework.ai.moderation.ModerationResponse;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static io.github.springai.harness.advisor.ModerationTestResponses.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InputModerationAdvisorTest {

	private static final AdvisorChain NOOP_CHAIN = new AdvisorChain() {
	};

	@Test
	void collectsRecentUserMessagesInChronologicalOrderAndExcludesOtherRoles() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(false));
		InputModerationAdvisor advisor = InputModerationAdvisor.builder(model).maxModerationCharacters(12).build();
		ChatClientRequest request = request(List.of(new SystemMessage("system"), new UserMessage("discarded"),
				new AssistantMessage("assistant"), new UserMessage("hello"), new UserMessage("world")));

		assertThat(advisor.before(request, NOOP_CHAIN)).isSameAs(request);
		assertThat(model.inputs()).containsExactly("hello\nworld");
	}

	@Test
	void keepsSuffixOfBoundaryMessageWhenRecentHistoryExceedsLimit() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(false));
		InputModerationAdvisor advisor = InputModerationAdvisor.builder(model).maxModerationCharacters(10).build();

		advisor.before(request(List.of(new UserMessage("123456789"), new UserMessage("ABCD"))), NOOP_CHAIN);

		assertThat(model.inputs()).containsExactly("56789\nABCD");
	}

	@Test
	void countsStringLengthAndDoesNotSplitSurrogatePairAtInputBoundary() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(false));
		InputModerationAdvisor advisor = InputModerationAdvisor.builder(model).maxModerationCharacters(5).build();

		advisor.before(request(List.of(new UserMessage("😀😀"), new UserMessage("z"))), NOOP_CHAIN);

		assertThat(model.inputs()).containsExactly("😀\nz");
		assertThat(model.inputs().get(0).length()).isEqualTo(4);
	}

	@Test
	void rejectsContentSplitAcrossUserMessagesForCall() {
		RecordingModerationModel model = new RecordingModerationModel(
				text -> response(text.contains("unsafe\ncontent")));
		InputModerationAdvisor advisor = InputModerationAdvisor.builder(model).build();

		assertThatThrownBy(() -> advisor.before(
				request(List.of(new UserMessage("unsafe"), new UserMessage("content"))), NOOP_CHAIN))
				.isInstanceOfSatisfying(ModerationViolationException.class,
						exception -> assertThat(exception.getStage()).isEqualTo(ModerationViolationException.Stage.INPUT));
	}

	@Test
	void streamingViolationReturnsTerminalResponseWithoutCallingDownstream() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(true));
		InputModerationAdvisor advisor = InputModerationAdvisor.builder(model).build();
		TestStreamAdvisorChain chain = new TestStreamAdvisorChain(Flux.empty());
		ChatClientRequest request = ChatClientRequest.builder()
				.prompt(Prompt.builder().messages(new UserMessage("unsafe")).build())
				.context(Map.of("conversation", "c1"))
				.build();

		List<ChatClientResponse> responses = advisor.adviseStream(request, chain).collectList().block();

		assertThat(responses).hasSize(1);
		ChatClientResponse response = responses.get(0);
		assertThat(response.context()).containsEntry("conversation", "c1");
		assertThat(response.chatResponse().getResult().getMetadata().getFinishReason()).isEqualTo("content_filter");
		assertThat(response.chatResponse().getResult().getOutput().getMetadata())
				.containsEntry("stop", true)
				.containsEntry("moderation_error", "Input content was rejected by moderation");
		assertThat(chain.calls()).isZero();
	}

	@Test
	void safeStreamingInputInvokesDownstreamAndAfterPassesThrough() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(false));
		InputModerationAdvisor advisor = InputModerationAdvisor.builder(model).build();
		ChatClientResponse downstreamResponse = ChatClientResponse.builder().build();
		TestStreamAdvisorChain chain = new TestStreamAdvisorChain(Flux.just(downstreamResponse));

		assertThat(advisor.adviseStream(request(List.of(new UserMessage("safe"))), chain).collectList().block())
				.containsExactly(downstreamResponse);
		assertThat(advisor.after(downstreamResponse, NOOP_CHAIN)).isSameAs(downstreamResponse);
		assertThat(chain.calls()).isOne();
	}

	@Test
	void skipsBoundaryMessageWhenOnlyHalfOfSurrogatePairFits() {
		RecordingModerationModel model = new RecordingModerationModel(text -> response(false));
		InputModerationAdvisor advisor = InputModerationAdvisor.builder(model).maxModerationCharacters(3).build();

		advisor.before(request(List.of(new UserMessage("😀"), new UserMessage("z"))), NOOP_CHAIN);

		assertThat(model.inputs()).containsExactly("z");
	}

	@Test
	void moderationModelFailureFailsOpenAndMarksCurrentObservation() {
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
		InputModerationAdvisor advisor = InputModerationAdvisor.builder(model).observationRegistry(registry).build();
		ChatClientRequest request = request(List.of(new UserMessage("hello")));
		Observation observation = Observation.start("test", registry);

		try (Observation.Scope scope = observation.openScope()) {
			assertThat(advisor.before(request, NOOP_CHAIN)).isSameAs(request);
		}
		finally {
			observation.stop();
		}

		assertThat(errors).containsExactly(failure);
	}

	@Test
	void builderValidatesConfigurationAndSupportsCustomOrder() {
		ModerationModel model = prompt -> response(false);
		InputModerationAdvisor advisor = InputModerationAdvisor.builder(model).order(42).build();

		assertThat(advisor.getOrder()).isEqualTo(42);
		assertThat(InputModerationAdvisor.builder(model).build().getOrder())
				.isEqualTo(Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 10);
		assertThatThrownBy(() -> InputModerationAdvisor.builder(model).maxModerationCharacters(0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> InputModerationAdvisor.builder(model).observationRegistry(null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void emptyUserInputDoesNotInvokeModerationModel() {
		AtomicInteger calls = new AtomicInteger();
		ModerationModel model = prompt -> {
			calls.incrementAndGet();
			return response(false);
		};
		InputModerationAdvisor advisor = InputModerationAdvisor.builder(model).build();
		ChatClientRequest request = request(List.of(new SystemMessage("system"), new AssistantMessage("assistant")));

		assertThat(advisor.before(request, NOOP_CHAIN)).isSameAs(request);
		assertThat(calls).hasValue(0);
	}

	private ChatClientRequest request(List<Message> messages) {
		return ChatClientRequest.builder().prompt(new Prompt(messages)).build();
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

		private int calls;

		private TestStreamAdvisorChain(Flux<ChatClientResponse> responses) {
			this.responses = responses;
		}

		@Override
		public Flux<ChatClientResponse> nextStream(ChatClientRequest request) {
			this.calls++;
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

		int calls() {
			return this.calls;
		}

	}

}
