package io.github.springai.harness.advisor;

import io.github.springai.harness.storage.StorageProvider;
import io.github.springai.harness.tool.AutoMemoryTools;
import io.github.springai.harness.util.MemoryUtil;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * @author Christian Tzolov
 * @author ichaobuster
 */
public class AutoMemoryToolsAdvisor implements BaseChatMemoryAdvisor {

	private final int order;

	private final String memorySystemPrompt;

	private final List<ToolCallback> memoryToolCallbacks;

	private final BiPredicate<ChatClientRequest, Instant> memoryConsolidationTrigger;

	private AutoMemoryToolsAdvisor(int order, String memorySystemPrompt, List<ToolCallback> memoryToolCallbacks,
								   BiPredicate<ChatClientRequest, Instant> memoryConsolidationTrigger) {
		this.order = order;
		this.memorySystemPrompt = memorySystemPrompt;
		this.memoryToolCallbacks = memoryToolCallbacks;
		this.memoryConsolidationTrigger = memoryConsolidationTrigger;
	}

	public String getMemorySystemPrompt() {
		return this.memorySystemPrompt;
	}

	@Override
	public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
		if (chatClientRequest.prompt().getOptions() instanceof ToolCallingChatOptions toolOptions) {
			Prompt augPrompt = chatClientRequest.prompt()
					.augmentSystemMessage(chatClientRequest.prompt().getSystemMessage().getText() + System.lineSeparator()
							+ System.lineSeparator() + this.memorySystemPrompt + System.lineSeparator()
							+ System.lineSeparator()
							+ (this.memoryConsolidationTrigger.test(chatClientRequest, Instant.now())
							? "<system-reminder>Consolidate the long-term memory by summarizing and removing redundant information.</system-reminder>"
							: ""));

			List<ToolCallback> toolCallbacks = toolOptions.getToolCallbacks();

			Set<String> existingNames = toolCallbacks.stream()
					.map(tc -> tc.getToolDefinition().name())
					.collect(java.util.stream.Collectors.toSet());

			this.memoryToolCallbacks.stream()
					.filter(tc -> !existingNames.contains(tc.getToolDefinition().name()))
					.forEach(toolCallbacks::add);

			return chatClientRequest.mutate().prompt(augPrompt.mutate().chatOptions(toolOptions).build()).build();

		}

		return chatClientRequest;
	}

	@Override
	public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
		// Memory persistence is handled by the model itself via MemoryTools during the
		// call.
		return chatClientResponse;
	}

	@Override
	public int getOrder() {
		return order;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		// Before the default ToolCallingAdvisor which is at HIGHEST_PRECEDENCE + 300
		private int order = BaseAdvisor.HIGHEST_PRECEDENCE + 200;

		private StorageProvider memoryStorage;

		private String memorySystemPromptTemplate = MemoryUtil.DEFAULT_MEMORY_SYSTEM_PROMPT_TEMPLATE;

		private String typesOfMemory = MemoryUtil.DEFAULT_TYPES_OF_MEMORY;

		private String whatNotToSaveInMemory = MemoryUtil.DEFAULT_WHAT_NOT_TO_SAVE_IN_MEMORY;

		private String memorySystemPrompt;

		// 触发条件
		private BiPredicate<ChatClientRequest, Instant> memoryConsolidationTrigger = (request, instant) -> false;

		private Builder() {
		}

		public Builder order(int order) {
			this.order = order;
			return this;
		}

		public Builder memoryStorage(StorageProvider memoryStorage) {
			this.memoryStorage = memoryStorage;
			return this;
		}

		public Builder memorySystemPromptTemplate(String memorySystemPromptTemplate) {
			Assert.notNull(memorySystemPromptTemplate, "Memory system prompt template must not be null");
			this.memorySystemPromptTemplate = memorySystemPromptTemplate;
			return this;
		}

		public Builder typesOfMemory(String typesOfMemory) {
			Assert.notNull(typesOfMemory, "Types of memory must not be null");
			this.typesOfMemory = typesOfMemory;
			return this;
		}

		public Builder whatNotToSaveInMemory(String whatNotToSaveInMemory) {
			Assert.notNull(whatNotToSaveInMemory, "What not to save in memory must not be null");
			this.whatNotToSaveInMemory = whatNotToSaveInMemory;
			return this;
		}

		public Builder memorySystemPrompt(String memorySystemPrompt) {
			Assert.notNull(memorySystemPrompt, "Memory system prompt must not be null");
			this.memorySystemPrompt = memorySystemPrompt;
			return this;
		}

		public Builder memoryConsolidationTrigger(BiPredicate<ChatClientRequest, Instant> memoryConsolidationTrigger) {
			Assert.notNull(memoryConsolidationTrigger, "Memory consolidation trigger must not be null");
			this.memoryConsolidationTrigger = memoryConsolidationTrigger;
			return this;
		}

		public AutoMemoryToolsAdvisor build() {
			Assert.notNull(memoryStorage, "memoryStorage must not be null");

			List<ToolCallback> memoryToolCallbacks = Arrays.asList(MethodToolCallbackProvider.builder()
					.toolObjects(AutoMemoryTools.builder().memoryStorage(this.memoryStorage).build())
					.build()
					.getToolCallbacks());

			String memorySystemPromptText = this.memorySystemPrompt;
			if (!StringUtils.hasText(memorySystemPromptText)) {
				memorySystemPromptText = MemoryUtil.createAutoMemoryToolsSystemPrompt(this.memorySystemPromptTemplate, this.typesOfMemory, this.whatNotToSaveInMemory);
			}

			return new AutoMemoryToolsAdvisor(this.order, memorySystemPromptText, memoryToolCallbacks,
					this.memoryConsolidationTrigger);
		}

	}
}
