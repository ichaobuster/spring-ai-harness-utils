package io.github.springai.harness.masking.recognizer;

import io.github.springai.harness.masking.C2DataMatch;
import io.github.springai.harness.masking.C2DataType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MainlandChinaIdentityCardRecognizerTest {

	private final MainlandChinaIdentityCardRecognizer recognizer = new MainlandChinaIdentityCardRecognizer();

	@Test
	void detectsChecksumValidIdentityCards() {
		assertThat(this.recognizer.detect("身份证11010519491231002X")).singleElement()
				.returns(C2DataType.ID_CARD, C2DataMatch::type)
				.returns(3, C2DataMatch::start)
				.returns(21, C2DataMatch::end);
		assertThat(this.recognizer.detect("11010519491231002x")).hasSize(1);
	}

	@Test
	void rejectsInvalidChecksumDateFutureDateAndEmbeddedValues() {
		assertThat(this.recognizer.detect("110105194912310021")).isEmpty();
		assertThat(this.recognizer.detect("11010519990230002X")).isEmpty();
		assertThat(this.recognizer.detect("11010520991231002X")).isEmpty();
		assertThat(this.recognizer.detect("A11010519491231002X")).isEmpty();
	}

	@Test
	void exposesItsBoundaryAndHandlesEmptyText() {
		assertThat(this.recognizer.maxMatchLength()).isEqualTo(18);
		assertThat(this.recognizer.maxLookbehindLength()).isEqualTo(1);
		assertThat(this.recognizer.maxLookaheadLength()).isEqualTo(1);
		assertThat(this.recognizer.detect(null)).isEmpty();
		assertThat(this.recognizer.detect("")).isEmpty();
	}

}
