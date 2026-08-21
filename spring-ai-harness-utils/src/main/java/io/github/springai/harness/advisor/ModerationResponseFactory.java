package io.github.springai.harness.advisor;

import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ModerationResponseFactory {

	private ModerationResponseFactory() {
	}

	static ChatClientResponse stopped(ChatClientResponse source, Map<String, Object> fallbackContext,
			ModerationViolationException.Stage stage) {
		return stopped(source, fallbackContext, stage, null);
	}

	static ChatClientResponse stopped(ChatClientResponse source, Map<String, Object> fallbackContext,
			ModerationViolationException.Stage stage, ViolationDetails details) {
		ModerationViolationException violation = new ModerationViolationException(stage);
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put(ModerationViolationException.STOP_METADATA_KEY, true);
		properties.put(ModerationViolationException.ERROR_METADATA_KEY, violation.getMessage());
		if (details != null) {
			properties.put(ModerationViolationException.GENERATION_INDEX_METADATA_KEY, details.generationIndex());
			properties.put(ModerationViolationException.WINDOW_START_METADATA_KEY, details.windowStart());
			properties.put(ModerationViolationException.WINDOW_END_METADATA_KEY, details.windowEnd());
			properties.put(ModerationViolationException.SAFE_THROUGH_METADATA_KEY, details.safeThrough());
		}
		AssistantMessage message = AssistantMessage.builder()
				.content("")
				.properties(properties)
				.build();

		ChatGenerationMetadata metadata = terminalMetadata(source, details);
		ChatResponse.Builder chatResponseBuilder = ChatResponse.builder();
		if (source != null && source.chatResponse() != null) {
			chatResponseBuilder.from(source.chatResponse());
		}
		ChatResponse chatResponse = chatResponseBuilder.generations(List.of(new Generation(message, metadata))).build();

		if (source != null) {
			return source.mutate().chatResponse(chatResponse).build();
		}
		return ChatClientResponse.builder().chatResponse(chatResponse).context(fallbackContext).build();
	}

	static boolean isStopped(ChatClientResponse response) {
		return response != null && response.chatResponse() != null && response.chatResponse().getResult() != null
				&& Boolean.TRUE.equals(response.chatResponse()
						.getResult()
						.getOutput()
						.getMetadata()
						.get(ModerationViolationException.STOP_METADATA_KEY));
	}

	private static ChatGenerationMetadata terminalMetadata(ChatClientResponse source, ViolationDetails details) {
		ChatGenerationMetadata.Builder builder = ChatGenerationMetadata.builder();
		ChatGenerationMetadata sourceMetadata = details == null ? null : details.generationMetadata();
		if (sourceMetadata == null && source != null && source.chatResponse() != null
				&& source.chatResponse().getResult() != null) {
			sourceMetadata = source.chatResponse().getResult().getMetadata();
		}
		if (sourceMetadata != null) {
			for (Map.Entry<String, Object> entry : sourceMetadata.entrySet()) {
				builder.metadata(entry.getKey(), entry.getValue());
			}
			builder.contentFilters(sourceMetadata.getContentFilters());
		}
		return builder.finishReason(ModerationViolationException.FINISH_REASON).build();
	}

	record ViolationDetails(int generationIndex, long windowStart, long windowEnd, long safeThrough,
			ChatGenerationMetadata generationMetadata) {
	}

}
