package io.github.springai.harness.advisor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.DefaultChatClient;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@DisplayName("MaxAgentLoopAdvisor Tests")
@ExtendWith(MockitoExtension.class)
class MaxAgentLoopAdvisorTest {

	@Mock
	AdvisorChain advisorChain;

	private static ChatClientRequest request(Prompt prompt, Map<String, Object> context) {
		return ChatClientRequest.builder().prompt(prompt).context(context).build();
	}

	@Test
	@DisplayName("Returns request unchanged when instructions are empty")
	void passesThroughWhenNoInstructions() {
		MaxAgentLoopAdvisor advisor = MaxAgentLoopAdvisor.builder().build();

		ChatClientRequest request = ChatClientRequest.builder().prompt(new Prompt(List.of())).build();
		ChatClientRequest result = advisor.before(request, advisorChain);

		assertThat(result).isSameAs(request);
	}

	@Test
	@DisplayName("Returns request unchanged when counter is missing from context")
	void passesThroughWhenNoCounterInContext() {
		MaxAgentLoopAdvisor advisor = MaxAgentLoopAdvisor.builder().build();
		Prompt prompt = new Prompt(List.of(new UserMessage("hello")));

		ChatClientRequest request = request(prompt, new HashMap<>());
		ChatClientRequest result = advisor.before(request, advisorChain);

		assertThat(result).isSameAs(request);
	}

	@Test
	@DisplayName("Increments counter on each before() call without exceeding limit")
	void incrementsCounterWithinLimit() {
		MaxAgentLoopAdvisor advisor = MaxAgentLoopAdvisor.builder()
				.maxLoopRounds(3)
				.build();

		Prompt prompt = new Prompt(List.of(new UserMessage("hello")));

		Map<String, Object> context = new HashMap<>();
		AtomicInteger counter = new AtomicInteger(0);
		context.put(MaxAgentLoopAdvisor.LOOP_ROUND_COUNTER_KEY, counter);

		ChatClientRequest request = request(prompt, context);

		// 第 1 轮：计数器从 0 自增到 1，未超限
		ChatClientRequest result1 = advisor.before(request, advisorChain);
		assertThat(result1).isSameAs(request);
		assertThat(counter.get()).isEqualTo(1);

		// 第 2 轮：计数器从 1 自增到 2，未超限
		ChatClientRequest result2 = advisor.before(request, advisorChain);
		assertThat(result2).isSameAs(request);
		assertThat(counter.get()).isEqualTo(2);

		// 第 3 轮：计数器从 2 自增到 3，未超限（等于 maxLoopRounds 但不超过）
		ChatClientRequest result3 = advisor.before(request, advisorChain);
		assertThat(result3).isSameAs(request);
		assertThat(counter.get()).isEqualTo(3);
	}

	@Test
	@DisplayName("Throws MaxAgentLoopException when loop rounds exceed limit")
	void throwsExceptionWhenLimitExceeded() {
		MaxAgentLoopAdvisor advisor = MaxAgentLoopAdvisor.builder()
				.maxLoopRounds(2)
				.build();

		Prompt prompt = new Prompt(List.of(new UserMessage("hello")));

		Map<String, Object> context = new HashMap<>();
		AtomicInteger counter = new AtomicInteger(2); // 已完成 2 轮，下一轮将超限
		context.put(MaxAgentLoopAdvisor.LOOP_ROUND_COUNTER_KEY, counter);

		ChatClientRequest request = request(prompt, context);

		assertThatThrownBy(() -> advisor.before(request, advisorChain))
				.isInstanceOf(MaxAgentLoopAdvisor.MaxAgentLoopException.class)
				.hasMessageContaining("2");

		assertThat(counter.get()).isEqualTo(3);
	}

	@Test
	@DisplayName("MaxAgentLoopException carries maxLoopRounds value")
	void exceptionCarriesMaxLoopRounds() {
		MaxAgentLoopAdvisor.MaxAgentLoopException ex = new MaxAgentLoopAdvisor.MaxAgentLoopException(15);
		assertThat(ex.getMaxLoopRounds()).isEqualTo(15);
		assertThat(ex.getMessage()).contains("15");
	}

	@Test
	@DisplayName("Passes through after method")
	void passesThroughAfter() {
		MaxAgentLoopAdvisor advisor = MaxAgentLoopAdvisor.builder().build();
		ChatClientResponse response = mock(ChatClientResponse.class);

		ChatClientResponse result = advisor.after(response, advisorChain);
		assertThat(result).isSameAs(response);
	}

	@Test
	@DisplayName("Verifies advisorSpecConsumer configures advisor and counter in context")
	void testAdvisorSpecConsumer() {
		MaxAgentLoopAdvisor advisor = MaxAgentLoopAdvisor.builder().build();

		DefaultChatClient.DefaultAdvisorSpec advisorSpec = new DefaultChatClient.DefaultAdvisorSpec();
		advisor.advisorSpecConsumer().accept(advisorSpec);

		assertThat(advisorSpec.getAdvisors()).contains(advisor);
		assertThat(advisorSpec.getParams()).containsKey(MaxAgentLoopAdvisor.LOOP_ROUND_COUNTER_KEY);
		assertThat(advisorSpec.getParams().get(MaxAgentLoopAdvisor.LOOP_ROUND_COUNTER_KEY)).isInstanceOf(AtomicInteger.class);
	}

	@Test
	@DisplayName("Verifies builder validation and custom order")
	void testBuilderValidation() {
		assertThatThrownBy(() -> MaxAgentLoopAdvisor.builder().maxLoopRounds(0))
				.isInstanceOf(IllegalArgumentException.class);

		MaxAgentLoopAdvisor customAdvisor = MaxAgentLoopAdvisor.builder()
				.order(123)
				.maxLoopRounds(10)
				.build();

		assertThat(customAdvisor.getOrder()).isEqualTo(123);
	}

}
