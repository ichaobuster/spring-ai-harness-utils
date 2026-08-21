package io.github.springai.harness.advisor;

import org.springframework.util.Assert;

/**
 * Raised when input or output content is rejected by a moderation model.
 */
public final class ModerationViolationException extends RuntimeException {

	public static final String FINISH_REASON = "content_filter";

	public static final String STOP_METADATA_KEY = "stop";

	public static final String ERROR_METADATA_KEY = "moderation_error";

	public static final String GENERATION_INDEX_METADATA_KEY = "moderation_generation_index";

	public static final String WINDOW_START_METADATA_KEY = "moderation_window_start";

	public static final String WINDOW_END_METADATA_KEY = "moderation_window_end";

	public static final String SAFE_THROUGH_METADATA_KEY = "moderation_safe_through";

	private final Stage stage;

	public ModerationViolationException(Stage stage) {
		super(messageFor(stage));
		this.stage = stage;
	}

	public Stage getStage() {
		return this.stage;
	}

	public enum Stage {

		INPUT,

		OUTPUT

	}

	private static String messageFor(Stage stage) {
		Assert.notNull(stage, "stage must not be null");
		return stage == Stage.INPUT ? "Input content was rejected by moderation"
				: "Output content was rejected by moderation";
	}

}
