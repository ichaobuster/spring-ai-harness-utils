package io.github.springai.harness.masking.recognizer;

import io.github.springai.harness.masking.C2DataMatch;
import io.github.springai.harness.masking.C2DataType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailPaymentAccountRecognizerTest {

	private final EmailPaymentAccountRecognizer recognizer = new EmailPaymentAccountRecognizer();

	@Test
	void detectsEmailFormPaymentAccounts() {
		String text = "账号alice@example.com，备用a@pay.example.org";

		assertThat(this.recognizer.detect(text)).hasSize(2)
				.extracting(C2DataMatch::type)
				.containsOnly(C2DataType.PAYMENT_ACCOUNT);
	}

	@Test
	void rejectsUnsupportedAndOverlongAddresses() {
		String overlong = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "."
				+ "d".repeat(63) + ".com";

		assertThat(this.recognizer.detect("alice@localhost")).isEmpty();
		assertThat(this.recognizer.detect(overlong)).isEmpty();
		assertThat(this.recognizer.detect(".alice@example.com")).isEmpty();
		assertThat(this.recognizer.detect("alice..bob@example.com")).isEmpty();
		assertThat(this.recognizer.detect("alice.@example.com")).isEmpty();
	}

	@Test
	void exposesItsBoundaryAndHandlesEmptyText() {
		assertThat(this.recognizer.maxMatchLength()).isEqualTo(254);
		assertThat(this.recognizer.maxLookbehindLength()).isEqualTo(1);
		assertThat(this.recognizer.maxLookaheadLength()).isEqualTo(1);
		assertThat(this.recognizer.detect(null)).isEmpty();
		assertThat(this.recognizer.detect("")).isEmpty();
	}

}
