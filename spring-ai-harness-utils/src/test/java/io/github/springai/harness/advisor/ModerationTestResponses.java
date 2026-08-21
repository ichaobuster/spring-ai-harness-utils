package io.github.springai.harness.advisor;

import org.springframework.ai.moderation.Generation;
import org.springframework.ai.moderation.Moderation;
import org.springframework.ai.moderation.ModerationResponse;
import org.springframework.ai.moderation.ModerationResult;

import java.util.List;

final class ModerationTestResponses {

	private ModerationTestResponses() {
	}

	static ModerationResponse response(boolean flagged) {
		ModerationResult result = ModerationResult.builder().flagged(flagged).build();
		return new ModerationResponse(new Generation(Moderation.builder().results(List.of(result)).build()));
	}

	static ModerationResponse invalidResponse() {
		return new ModerationResponse(new Generation());
	}

}
