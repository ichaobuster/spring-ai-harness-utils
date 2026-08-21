package io.github.springai.harness.masking.recognizer;

import com.google.i18n.phonenumbers.PhoneNumberMatch;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import io.github.springai.harness.masking.C2DataMatch;
import io.github.springai.harness.masking.C2DataRecognizer;
import io.github.springai.harness.masking.C2DataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Detects valid phone numbers using libphonenumber.
 */
public final class PhoneNumberRecognizer implements C2DataRecognizer {

	private static final int MAX_MATCH_LENGTH = 64;

	private final String defaultRegion;

	private final PhoneNumberUtil phoneNumberUtil;

	public PhoneNumberRecognizer(String defaultRegion) {
		if (defaultRegion == null || defaultRegion.isBlank()) {
			throw new IllegalArgumentException("defaultRegion must not be blank");
		}
		this.phoneNumberUtil = PhoneNumberUtil.getInstance();
		this.defaultRegion = defaultRegion.toUpperCase(Locale.ROOT);
		if (!this.phoneNumberUtil.getSupportedRegions().contains(this.defaultRegion)) {
			throw new IllegalArgumentException("defaultRegion must be a supported phone region");
		}
	}

	@Override
	public List<C2DataMatch> detect(String text) {
		if (text == null || text.isEmpty()) {
			return List.of();
		}
		List<C2DataMatch> matches = new ArrayList<>();
		Iterable<PhoneNumberMatch> phoneMatches = this.phoneNumberUtil.findNumbers(text, this.defaultRegion,
				PhoneNumberUtil.Leniency.VALID, Long.MAX_VALUE);
		for (PhoneNumberMatch match : phoneMatches) {
			if (match.end() - match.start() <= MAX_MATCH_LENGTH) {
				matches.add(new C2DataMatch(C2DataType.PHONE_NUMBER, match.start(), match.end()));
			}
		}
		return List.copyOf(matches);
	}

	@Override
	public int maxMatchLength() {
		return MAX_MATCH_LENGTH;
	}

	@Override
	public int maxLookbehindLength() {
		return 1;
	}

	@Override
	public int maxLookaheadLength() {
		return 1;
	}

}
