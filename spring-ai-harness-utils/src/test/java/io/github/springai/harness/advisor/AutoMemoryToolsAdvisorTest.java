package io.github.springai.harness.advisor;

import io.github.springai.harness.storage.LocalFileStorage;
import io.github.springai.harness.storage.StorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for {@link AutoMemoryToolsAdvisor}.
 *
 * @author ichaobuster
 */
@DisplayName("AutoMemoryToolsAdvisor Tests")
@ExtendWith(MockitoExtension.class)
class AutoMemoryToolsAdvisorTest {

	@TempDir
	Path tempDir;

	@Mock
	AdvisorChain advisorChain;

	StorageProvider memoryStorage;

	@BeforeEach
	void setup() {
		memoryStorage = LocalFileStorage.builder()
				.baseDir(tempDir)
				.build();
	}

	private AutoMemoryToolsAdvisor advisor(String promptText) {
		return AutoMemoryToolsAdvisor.builder()
				.memoryStorage(memoryStorage)
				.memorySystemPrompt(promptText)
				.build();
	}

	// -------------------------------------------------------------------------
	// Builder
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("Builder")
	class BuilderTests {

		@Test
		@DisplayName("Default order is HIGHEST_PRECEDENCE + 200")
		void defaultOrder() {
			AutoMemoryToolsAdvisor a = AutoMemoryToolsAdvisor.builder()
					.memoryStorage(memoryStorage)
					.build();
			assertThat(a.getOrder()).isEqualTo(BaseAdvisor.HIGHEST_PRECEDENCE + 200);
		}

		@Test
		@DisplayName("Custom order is respected")
		void customOrder() {
			AutoMemoryToolsAdvisor a = AutoMemoryToolsAdvisor.builder()
					.memoryStorage(memoryStorage)
					.order(42)
					.build();
			assertThat(a.getOrder()).isEqualTo(42);
		}

		@Test
		@DisplayName("Null memoryStorage throws")
		void nullMemoryStorageThrows() {
			assertThatIllegalArgumentException()
					.isThrownBy(() -> AutoMemoryToolsAdvisor.builder().build());
		}

		@Test
		@DisplayName("Null memorySystemPromptTemplate throws immediately")
		void nullPromptTemplateThrows() {
			assertThatIllegalArgumentException()
					.isThrownBy(() -> AutoMemoryToolsAdvisor.builder()
							.memoryStorage(memoryStorage)
							.memorySystemPromptTemplate(null));
		}

		@Test
		@DisplayName("Null memorySystemPrompt throws immediately")
		void nullPromptThrows() {
			assertThatIllegalArgumentException()
					.isThrownBy(() -> AutoMemoryToolsAdvisor.builder().memorySystemPrompt(null));
		}

		@Test
		@DisplayName("Null typesOfMemory throws immediately")
		void nullTypesOfMemoryThrows() {
			assertThatIllegalArgumentException()
					.isThrownBy(() -> AutoMemoryToolsAdvisor.builder().typesOfMemory(null));
		}

		@Test
		@DisplayName("Null whatNotToSaveInMemory throws immediately")
		void nullWhatNotToSaveInMemoryThrows() {
			assertThatIllegalArgumentException()
					.isThrownBy(() -> AutoMemoryToolsAdvisor.builder().whatNotToSaveInMemory(null));
		}

		@Test
		@DisplayName("Null memoryConsolidationTrigger throws immediately")
		void nullConsolidationTriggerThrows() {
			assertThatIllegalArgumentException()
					.isThrownBy(() -> AutoMemoryToolsAdvisor.builder().memoryConsolidationTrigger(null));
		}

		@Test
		@DisplayName("Works with custom MemoryStorage")
		void customMemoryStorage(@Mock StorageProvider memoryStorage) {
			AutoMemoryToolsAdvisor a = AutoMemoryToolsAdvisor.builder()
					.memoryStorage(memoryStorage)
					.build();
			assertThat(a).isNotNull();
		}

		@Test
		@DisplayName("Works with memorySystemPrompt")
		void customMemorySystemPrompt() {
			AutoMemoryToolsAdvisor a = AutoMemoryToolsAdvisor.builder()
					.memoryStorage(memoryStorage)
					.memorySystemPromptTemplate("full memorySystemPrompt")
					.build();
			assertThat(a).isNotNull();
			assertThat(a.getMemorySystemPrompt()).isEqualTo("full memorySystemPrompt");
		}

		@Test
		@DisplayName("Works with memorySystemPromptTemplate")
		void customMemorySystemPromptTemplate() {
			AutoMemoryToolsAdvisor a = AutoMemoryToolsAdvisor.builder()
					.memoryStorage(memoryStorage)
					.memorySystemPromptTemplate("This is the prompt: {TYPES_OF_MEMORY}; {WHAT_NOT_TO_SAVE_IN_MEMORY}")
					.typesOfMemory("This is typesOfMemory")
					.whatNotToSaveInMemory("This is whatNotToSaveInMemory")
					.build();
			assertThat(a).isNotNull();
			assertThat(a.getMemorySystemPrompt()).isEqualTo("This is the prompt: This is typesOfMemory; This is whatNotToSaveInMemory");
		}

	}

	// -------------------------------------------------------------------------
	// after()
	// -------------------------------------------------------------------------

	@Test
	@DisplayName("after() passes the response through unchanged")
	void afterPassesThrough() {
		ChatClientResponse response = ChatClientResponse.builder().context(Map.of()).build();
		assertThat(advisor("prompt").after(response, advisorChain)).isSameAs(response);
	}

	// -------------------------------------------------------------------------
	// before() — system prompt augmentation
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("before() — system prompt")
	class BeforeSystemPromptTests {

		@Test
		@DisplayName("Returns request unchanged when no ToolCallingChatOptions")
		void passesThoughWhenNoOptions() {
			ChatClientRequest request = request(new Prompt(new UserMessage("hi")));

			ChatClientRequest result = advisor("MEMORY_INSTRUCTIONS").before(request, advisorChain);

			assertThat(result).isSameAs(request);
		}

		@Test
		@DisplayName("Injects memory prompt when ToolCallingChatOptions present")
		void injectsWithToolCallingOptions() {
			Prompt prompt = new Prompt(List.of(new UserMessage("hi")), new OpenAiChatOptions());
			ChatClientRequest request = request(prompt);

			ChatClientRequest result = advisor("MEMORY_INSTRUCTIONS").before(request, advisorChain);

			assertThat(result.prompt().getSystemMessage().getText()).contains("MEMORY_INSTRUCTIONS");
		}

		@Test
		@DisplayName("Preserves existing system message text")
		void preservesExistingSystemText() {
			Prompt prompt = new Prompt(
					List.of(new SystemMessage("ORIGINAL"), new UserMessage("hi")),
					new OpenAiChatOptions());
			ChatClientRequest request = request(prompt);

			ChatClientRequest result = advisor("APPENDED").before(request, advisorChain);

			String systemText = result.prompt().getSystemMessage().getText();
			assertThat(systemText).contains("ORIGINAL").contains("APPENDED");
		}

	}

	// -------------------------------------------------------------------------
	// before() — memory consolidation trigger
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("before() — consolidation trigger")
	class ConsolidationTriggerTests {

		@Test
		@DisplayName("No consolidation reminder when trigger returns false (default)")
		void noReminderByDefault() {
			Prompt prompt = new Prompt(new UserMessage("hi"), new OpenAiChatOptions());
			ChatClientRequest request = request(prompt);

			ChatClientRequest result = advisor("MEMORY_PROMPT").before(request, advisorChain);

			assertThat(result.prompt().getSystemMessage().getText())
					.doesNotContain("system-reminder")
					.doesNotContain("Consolidate");
		}

		@Test
		@DisplayName("Consolidation reminder appended when trigger returns true")
		void reminderInjectedWhenTriggerTrue() {
			AutoMemoryToolsAdvisor a = AutoMemoryToolsAdvisor.builder()
					.memoryStorage(memoryStorage)
					.memorySystemPrompt("MEMORY_PROMPT")
					.memoryConsolidationTrigger((req, instant) -> true)
					.build();

			Prompt prompt = new Prompt(new UserMessage("hi"), new OpenAiChatOptions());
			ChatClientRequest result = a.before(request(prompt), advisorChain);

			assertThat(result.prompt().getSystemMessage().getText())
					.contains("Consolidate")
					.contains("system-reminder");
		}

		@Test
		@DisplayName("Trigger receives the original request and a non-null instant")
		void triggerReceivesCorrectArguments() {
			ChatClientRequest[] capturedRequest = new ChatClientRequest[1];
			java.time.Instant[] capturedInstant = new java.time.Instant[1];

			AutoMemoryToolsAdvisor a = AutoMemoryToolsAdvisor.builder()
					.memoryStorage(memoryStorage)
					.memorySystemPrompt("p")
					.memoryConsolidationTrigger((req, instant) -> {
						capturedRequest[0] = req;
						capturedInstant[0] = instant;
						return false;
					})
					.build();

			Prompt prompt = new Prompt(new UserMessage("hi"), new OpenAiChatOptions());
			ChatClientRequest originalRequest = request(prompt);
			a.before(originalRequest, advisorChain);

			assertThat(capturedRequest[0]).isSameAs(originalRequest);
			assertThat(capturedInstant[0]).isNotNull();
		}

	}

	// -------------------------------------------------------------------------
	// before() — tool callback registration
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("before() — tool callbacks")
	class BeforeToolCallbackTests {

		@Test
		@DisplayName("Registers memory tools when ToolCallingChatOptions present")
		void registersMemoryTools() {
			Prompt prompt = new Prompt(new UserMessage("hi"), new OpenAiChatOptions());
			ChatClientRequest request = request(prompt);

			ChatClientRequest result = advisor("prompt").before(request, advisorChain);

			OpenAiChatOptions opts = (OpenAiChatOptions) result.prompt().getOptions();
			assertThat(opts.getToolCallbacks()).isNotEmpty();
		}

		@Test
		@DisplayName("Registers all six MemoryTools by name")
		void registersAllSixMemoryTools() {
			Prompt prompt = new Prompt(new UserMessage("hi"), new OpenAiChatOptions());
			ChatClientRequest request = request(prompt);

			ChatClientRequest result = advisor("prompt").before(request, advisorChain);

			OpenAiChatOptions opts = (OpenAiChatOptions) result.prompt().getOptions();
			List<String> names = opts.getToolCallbacks().stream()
					.map(tc -> tc.getToolDefinition().name())
					.toList();

			assertThat(names).containsExactlyInAnyOrder(
					"MemoryView", "MemoryCreate", "MemoryStrReplace",
					"MemoryInsert", "MemoryDelete", "MemoryRename");
		}

		@Test
		@DisplayName("Does not add tool callbacks when options are not ToolCallingChatOptions")
		void noToolsWithoutToolCallingOptions() {
			ChatClientRequest request = request(new Prompt(new UserMessage("hi")));

			ChatClientRequest result = advisor("prompt").before(request, advisorChain);

			assertThat(result.prompt().getOptions()).isNull();
		}

		@Test
		@DisplayName("Skips duplicate memory tool callbacks by name")
		void skipsDuplicates() {
			AutoMemoryToolsAdvisor a = advisor("prompt");

			// First pass: collect the memory callbacks the advisor registers
			Prompt first = new Prompt(new UserMessage("hi"), new OpenAiChatOptions());
			List<ToolCallback> memoryCallbacks = ((OpenAiChatOptions) a.before(request(first), advisorChain)
					.prompt()
					.getOptions()).getToolCallbacks();

			// Second pass: pre-populate options with those same callbacks
			OpenAiChatOptions opts = new OpenAiChatOptions();
			opts.setToolCallbacks(memoryCallbacks);
			ChatClientRequest requestWithDups = request(new Prompt(new UserMessage("hi"), opts));

			ChatClientRequest result = a.before(requestWithDups, advisorChain);

			OpenAiChatOptions resultOpts = (OpenAiChatOptions) result.prompt().getOptions();
			List<String> names = resultOpts.getToolCallbacks()
					.stream()
					.map(tc -> tc.getToolDefinition().name())
					.toList();

			// Names must not be duplicated — count equals the unique memory tool count
			assertThat(names).doesNotHaveDuplicates().hasSize(memoryCallbacks.size());
		}

	}

	// -------------------------------------------------------------------------
	// helpers
	// -------------------------------------------------------------------------

	private static ChatClientRequest request(Prompt prompt) {
		return ChatClientRequest.builder().prompt(prompt).build();
	}

}
