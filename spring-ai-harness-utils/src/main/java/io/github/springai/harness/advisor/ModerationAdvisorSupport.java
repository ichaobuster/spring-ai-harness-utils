package io.github.springai.harness.advisor;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.moderation.Moderation;
import org.springframework.ai.moderation.ModerationModel;
import org.springframework.ai.moderation.ModerationPrompt;
import org.springframework.ai.moderation.ModerationResponse;
import org.springframework.ai.moderation.ModerationResult;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared moderation invocation, splitting, and fail-open handling.
 */
@Slf4j
final class ModerationAdvisorSupport {

	static final int DEFAULT_MAX_MODERATION_CHARACTERS = 5_000;

	private static final double SPLIT_RATIO = 0.9d;

	private final ModerationModel moderationModel;

	private final ObservationRegistry observationRegistry;

	private final CharacterTextSplitter textSplitter;

	private final int maxModerationCharacters;

	private final int splitCharacters;

	ModerationAdvisorSupport(ModerationModel moderationModel, int maxModerationCharacters,
			ObservationRegistry observationRegistry) {
		Assert.notNull(moderationModel, "moderationModel must not be null");
		Assert.isTrue(maxModerationCharacters > 0, "maxModerationCharacters must be greater than 0");
		Assert.notNull(observationRegistry, "observationRegistry must not be null");
		this.moderationModel = moderationModel;
		this.observationRegistry = observationRegistry;
		this.maxModerationCharacters = maxModerationCharacters;
		this.splitCharacters = splitCharactersFor(maxModerationCharacters);
		this.textSplitter = new CharacterTextSplitter(this.maxModerationCharacters,
				this.maxModerationCharacters - this.splitCharacters);
	}

	int maxModerationCharacters() {
		return this.maxModerationCharacters;
	}

	int overlapCharacters() {
		return this.maxModerationCharacters - this.splitCharacters;
	}

	int splitCharacters() {
		return this.splitCharacters;
	}

	static int splitCharactersFor(int maxModerationCharacters) {
		return Math.max(1, (int) Math.floor(maxModerationCharacters * SPLIT_RATIO));
	}

	Observation currentObservation() {
		return this.observationRegistry.getCurrentObservation();
	}

	boolean isFlagged(String text, ModerationViolationException.Stage stage, Observation observation) {
		if (!StringUtils.hasText(text)) {
			return false;
		}
		try {
			ModerationResponse response = this.moderationModel.call(new ModerationPrompt(text));
			return extractFlagged(response);
		}
		catch (RuntimeException ex) {
			log.warn("Moderation model failed during {} moderation; allowing content", stage.name().toLowerCase(), ex);
			if (observation != null) {
				observation.error(ex);
			}
			return false;
		}
	}

	boolean isAnySegmentFlagged(String text, ModerationViolationException.Stage stage, Observation observation) {
		return moderateSegments(text, stage, observation).flagged();
	}

	ModerationCheckResult moderateSegments(String text, ModerationViolationException.Stage stage,
			Observation observation) {
		for (TextWindow window : this.textSplitter.splitWindows(text)) {
			if (isFlagged(window.text(), stage, observation)) {
				return new ModerationCheckResult(true, window.start(), window.end());
			}
		}
		return ModerationCheckResult.safe();
	}

	record ModerationCheckResult(boolean flagged, int windowStart, int windowEnd) {

		private static ModerationCheckResult safe() {
			return new ModerationCheckResult(false, -1, -1);
		}

	}

	private record TextWindow(String text, int start, int end) {
	}

	private boolean extractFlagged(ModerationResponse response) {
		if (response == null || response.getResult() == null) {
			throw new IllegalStateException("Moderation model returned no result");
		}
		Moderation moderation = response.getResult().getOutput();
		if (moderation == null || moderation.getResults() == null || moderation.getResults().isEmpty()) {
			throw new IllegalStateException("Moderation model returned no moderation results");
		}
		return moderation.getResults().stream().anyMatch(ModerationResult::isFlagged);
	}

	private static final class CharacterTextSplitter extends TextSplitter {

		private final int maxCharacters;

		private final int overlapCharacters;

		private CharacterTextSplitter(int maxCharacters, int overlapCharacters) {
			this.maxCharacters = maxCharacters;
			this.overlapCharacters = overlapCharacters;
		}

		@Override
		protected List<String> splitText(String text) {
			return splitWindows(text).stream().map(TextWindow::text).toList();
		}

		private List<TextWindow> splitWindows(String text) {
			if (!StringUtils.hasText(text)) {
				return List.of();
			}
			List<TextWindow> chunks = new ArrayList<>();
			int start = 0;
			while (start < text.length()) {
				int end = Math.min(start + this.maxCharacters, text.length());
				if (end < text.length() && end - start > 1 && Character.isHighSurrogate(text.charAt(end - 1))
						&& Character.isLowSurrogate(text.charAt(end))) {
					end--;
				}
				chunks.add(new TextWindow(text.substring(start, end), start, end));
				if (end == text.length()) {
					break;
				}
				int nextStart = Math.max(0, end - this.overlapCharacters);
				if (this.overlapCharacters > 0 && nextStart > 0 && nextStart < text.length()
						&& Character.isLowSurrogate(text.charAt(nextStart))
						&& Character.isHighSurrogate(text.charAt(nextStart - 1))) {
					int surrogateStart = nextStart - 1;
					if (Math.min(surrogateStart + this.maxCharacters, text.length()) > end) {
						nextStart = surrogateStart;
					}
					else {
						nextStart++;
					}
				}
				if (nextStart <= start) {
					nextStart = end;
				}
				start = nextStart;
			}
			return chunks;
		}

	}

}
