package io.github.springai.harness.masking.recognizer;

import io.github.springai.harness.masking.C2DataMatch;
import io.github.springai.harness.masking.C2DataType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhoneNumberRecognizerTest {

	@Test
	void detectsChineseAndExplicitInternationalNumbers() {
		PhoneNumberRecognizer recognizer = new PhoneNumberRecognizer("CN");
		String text = "手机13800138000，国际号码+1 650-253-0000";

		List<C2DataMatch> matches = recognizer.detect(text);

		assertThat(matches).extracting(C2DataMatch::type)
				.containsExactly(C2DataType.PHONE_NUMBER, C2DataType.PHONE_NUMBER);
		assertThat(matches).extracting(match -> text.substring(match.start(), match.end()))
				.containsExactly("13800138000", "+1 650-253-0000");
	}

	@Test
	void appliesTheConfiguredDefaultRegion() {
		PhoneNumberRecognizer recognizer = new PhoneNumberRecognizer("us");

		assertThat(recognizer.detect("Call (650) 253-0000")).singleElement()
				.extracting(C2DataMatch::type)
				.isEqualTo(C2DataType.PHONE_NUMBER);
	}

	@Test
	void rejectsInvalidNumbersAndHandlesEmptyText() {
		PhoneNumberRecognizer recognizer = new PhoneNumberRecognizer("CN");

		assertThat(recognizer.detect("无效号码12345678901")).isEmpty();
		assertThat(recognizer.detect(null)).isEmpty();
		assertThat(recognizer.detect("")).isEmpty();
		assertThat(recognizer.maxMatchLength()).isEqualTo(64);
		assertThat(recognizer.maxLookbehindLength()).isEqualTo(1);
		assertThat(recognizer.maxLookaheadLength()).isEqualTo(1);
	}

	@Test
	void validatesTheDefaultRegion() {
		assertThatThrownBy(() -> new PhoneNumberRecognizer(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PhoneNumberRecognizer(" ")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PhoneNumberRecognizer("NOT_A_REGION"))
				.isInstanceOf(IllegalArgumentException.class);
	}

}
