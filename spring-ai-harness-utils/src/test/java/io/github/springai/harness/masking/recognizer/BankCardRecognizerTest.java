package io.github.springai.harness.masking.recognizer;

import io.github.springai.harness.masking.C2DataMatch;
import io.github.springai.harness.masking.C2DataType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BankCardRecognizerTest {

	private final BankCardRecognizer recognizer = new BankCardRecognizer();

	@Test
	void detectsLuhnValidCardsWithSupportedSeparators() {
		String text = "首卡 4111 1111 1111 1111，次卡 4111-1111-1111-1111";

		assertThat(this.recognizer.detect(text)).hasSize(2)
				.extracting(C2DataMatch::type)
				.containsOnly(C2DataType.BANK_CARD);
	}

	@Test
	void rejectsInvalidChecksumsAndUnsupportedLengths() {
		assertThat(this.recognizer.detect("4111 1111 1111 1112")).isEmpty();
		assertThat(this.recognizer.detect("12345678901")).isEmpty();
		assertThat(this.recognizer.detect("12345678901234567890")).isEmpty();
	}

	@Test
	void exposesItsBoundaryAndHandlesEmptyText() {
		assertThat(this.recognizer.maxMatchLength()).isEqualTo(37);
		assertThat(this.recognizer.detect(null)).isEmpty();
		assertThat(this.recognizer.detect("")).isEmpty();
	}

}
