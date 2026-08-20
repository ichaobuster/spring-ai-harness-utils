package io.github.springai.harness.masking;

import io.github.springai.harness.masking.recognizer.PhoneNumberRecognizer;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class C2DataMaskingServiceTest {

	private final C2DataMaskingService service = C2DataMaskingService.builder().build();

	@Test
	void detectsAndMasksBuiltInC2Data() {
		String text = "手机13800138000，身份证11010519491231002X，银行卡4111 1111 1111 1111，账号alice@example.com";

		List<C2DataMatch> matches = this.service.detect(text);

		assertThat(matches).extracting(C2DataMatch::type)
				.containsExactly(C2DataType.PHONE_NUMBER, C2DataType.ID_CARD, C2DataType.BANK_CARD,
						C2DataType.PAYMENT_ACCOUNT);
		assertThat(this.service.mask(text)).isEqualTo(
				"手机138****8000，身份证110***********002X，银行卡4111 **** **** 1111，账号a****@example.com");
	}

	@Test
	void preservesInternationalCountryCodeAndEmailDomain() {
		assertThat(this.service.mask("Call +86 13800138000 or a@example.com"))
				.isEqualTo("Call +86 138****8000 or *@example.com");
		assertThat(this.service.mask("Call +1 650-253-0000"))
				.isEqualTo("Call +1 650-***-0000");
	}

	@Test
	void supportsCustomMaskCharacter() {
		C2DataMaskingService custom = C2DataMaskingService.builder().maskCharacter('#').build();

		assertThat(custom.mask("13800138000 alice@example.com"))
				.isEqualTo("138####8000 a####@example.com");
	}

	@Test
	void defaultBuilderInstallsAllPresetRecognizers() {
		assertThat(this.service.getMaxMatchLength()).isEqualTo(254);
		assertThat(this.service.detect(
				"13800138000，11010519491231002X，4111 1111 1111 1111，alice@example.com"))
				.extracting(C2DataMatch::type)
				.containsExactly(C2DataType.PHONE_NUMBER, C2DataType.ID_CARD, C2DataType.BANK_CARD,
						C2DataType.PAYMENT_ACCOUNT);
	}

	@Test
	void replacesDefaultRecognizersAndUsesTheConfiguredMaximumLength() {
		C2DataMaskingService phoneOnly = C2DataMaskingService.builder()
				.recognizers(List.of(new PhoneNumberRecognizer("CN")))
				.build();

		assertThat(phoneOnly.getMaxMatchLength()).isEqualTo(64);
		assertThat(phoneOnly.detect("13800138000 alice@example.com"))
				.extracting(C2DataMatch::type)
				.containsExactly(C2DataType.PHONE_NUMBER);
	}

	@Test
	void emptyRecognizerSetDisablesDetectionAndStreamingBuffering() {
		C2DataMaskingService disabled = C2DataMaskingService.builder().recognizers(List.of()).build();
		C2DataMaskingService.StreamingMaskingSession session = disabled.newStreamingSession();

		assertThat(disabled.getMaxMatchLength()).isZero();
		assertThat(disabled.detect("13800138000 alice@example.com")).isEmpty();
		assertThat(session.accept("13800138000 alice@example.com")).isEqualTo("13800138000 alice@example.com");
		assertThat(session.finish()).isEmpty();
	}

	@Test
	void addRecognizerAppendsToDefaultsWhileRecognizersReplacesEarlierAdditions() {
		C2DataRecognizer customRecognizer = fixedSecretRecognizer();
		C2DataMaskingService appended = C2DataMaskingService.builder().addRecognizer(customRecognizer).build();
		C2DataMaskingService replaced = C2DataMaskingService.builder()
				.addRecognizer(customRecognizer)
				.recognizers(List.of())
				.build();

		assertThat(appended.mask("SECRET 13800138000")).isEqualTo("****** 138****8000");
		assertThat(replaced.mask("SECRET 13800138000")).isEqualTo("SECRET 13800138000");
	}

	@Test
	void passesTheConfiguredRegionToTheDefaultPhoneRecognizer() {
		C2DataMaskingService usService = C2DataMaskingService.builder().defaultPhoneRegion("US").build();

		assertThat(usService.detect("Call (650) 253-0000")).singleElement()
				.extracting(C2DataMatch::type)
				.isEqualTo(C2DataType.PHONE_NUMBER);
	}

	@Test
	void rejectsInvalidChecksumsAndOrdinaryBusinessNumbers() {
		String text = "无效手机12345678901，无效身份证110105194912310021，无效卡4111 1111 1111 1112，订单1234567890";

		assertThat(this.service.detect(text)).isEmpty();
		assertThat(this.service.mask(text)).isEqualTo(text);
	}

	@Test
	void rejectsInvalidIdentityCardDate() {
		String text = "11010519990230002X";

		assertThat(this.service.detect(text)).isEmpty();
	}

	@Test
	void resolvesOverlappingCustomMatchesByBuiltInPriority() {
		C2DataRecognizer recognizer = new C2DataRecognizer() {
			@Override
			public List<C2DataMatch> detect(String text) {
				return List.of(new C2DataMatch(C2DataType.CUSTOM, 0, text.length()));
			}

			@Override
			public int maxMatchLength() {
				return 32;
			}
		};
		C2DataMaskingService custom = C2DataMaskingService.builder().addRecognizer(recognizer).build();

		assertThat(custom.detect("13800138000")).extracting(C2DataMatch::type)
				.containsExactly(C2DataType.PHONE_NUMBER);
	}

	@Test
	void customRecognizerMasksEntireMatch() {
		C2DataRecognizer recognizer = fixedSecretRecognizer();

		C2DataMaskingService custom = C2DataMaskingService.builder().addRecognizer(recognizer).build();

		assertThat(custom.mask("value=SECRET")).isEqualTo("value=******");
	}

	@Test
	void streamingSessionMatchesWholeTextMaskingAcrossChunkBoundaries() {
		String text = "前缀" + "x".repeat(260) + " 手机13800138000，邮箱alice@example.com，身份证11010519491231002X";
		C2DataMaskingService.StreamingMaskingSession session = this.service.newStreamingSession();

		String result = session.accept(text.substring(0, 265))
				+ session.accept(text.substring(265, 273))
				+ session.accept(text.substring(273, 287))
				+ session.accept(text.substring(287))
				+ session.finish();

		assertThat(result).isEqualTo(this.service.mask(text));
		assertThat(result).doesNotContain("13800138000", "alice@example.com", "11010519491231002X");
	}

	@Test
	void streamingSessionCannotBeReusedAfterFinish() {
		C2DataMaskingService.StreamingMaskingSession session = this.service.newStreamingSession();
		session.accept("hello");
		assertThat(session.finish()).isEqualTo("hello");
		assertThat(session.finish()).isEmpty();
		assertThatThrownBy(() -> session.accept("again")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void streamingSessionRetainsAValueThatCrossesTheReleaseBoundary() {
		String text = "13800138000 " + "x".repeat(250);
		C2DataMaskingService.StreamingMaskingSession session = this.service.newStreamingSession();

		assertThat(session.accept(text)).isEmpty();
		assertThat(session.finish()).isEqualTo(this.service.mask(text));
	}

	@Test
	void handlesNullAndEmptyText() {
		assertThat(this.service.detect(null)).isEmpty();
		assertThat(this.service.detect("")).isEmpty();
		assertThat(this.service.mask(null)).isNull();
		assertThat(this.service.mask("")).isEmpty();
	}

	@Test
	void validatesBuilderInputsAndMatchRanges() {
		assertThat(this.service.getMaskCharacter()).isEqualTo('*');
		assertThat(this.service.getDefaultPhoneRegion()).isEqualTo("CN");
		assertThatThrownBy(() -> C2DataMaskingService.builder().defaultPhoneRegion(" ").build())
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new C2DataMatch(null, 0, 1)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new C2DataMatch(C2DataType.CUSTOM, 1, 1))
				.isInstanceOf(IllegalArgumentException.class);
		C2DataRecognizer invalidRecognizer = new C2DataRecognizer() {
			@Override
			public List<C2DataMatch> detect(String text) {
				return List.of();
			}

			@Override
			public int maxMatchLength() {
				return 0;
			}
		};
		assertThatThrownBy(() -> C2DataMaskingService.builder().addRecognizer(invalidRecognizer).build())
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> C2DataMaskingService.builder().addRecognizer(null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> C2DataMaskingService.builder().recognizers(null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> C2DataMaskingService.builder().recognizers(Arrays.asList(invalidRecognizer, null)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private C2DataRecognizer fixedSecretRecognizer() {
		return new C2DataRecognizer() {
			@Override
			public List<C2DataMatch> detect(String text) {
				int start = text.indexOf("SECRET");
				return start < 0 ? List.of() : List.of(new C2DataMatch(C2DataType.CUSTOM, start, start + 6));
			}

			@Override
			public int maxMatchLength() {
				return 6;
			}
		};
	}

}
