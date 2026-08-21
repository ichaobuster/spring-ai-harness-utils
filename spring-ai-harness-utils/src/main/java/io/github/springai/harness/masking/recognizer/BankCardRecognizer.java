package io.github.springai.harness.masking.recognizer;

import io.github.springai.harness.masking.C2DataMatch;
import io.github.springai.harness.masking.C2DataRecognizer;
import io.github.springai.harness.masking.C2DataType;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects 12-19 digit bank card numbers that pass the Luhn checksum.
 */
public final class BankCardRecognizer implements C2DataRecognizer {

	private static final int MAX_MATCH_LENGTH = 37;

	private static final Pattern PATTERN = Pattern
			.compile("(?<!\\d)(?<!\\d[ -])(?:\\d[ -]?){11,18}\\d(?!\\d)(?![ -]\\d)");

	@Override
	public List<C2DataMatch> detect(String text) {
		if (text == null || text.isEmpty()) {
			return List.of();
		}
		List<C2DataMatch> matches = new ArrayList<>();
		Matcher matcher = PATTERN.matcher(text);
		while (matcher.find()) {
			String candidate = matcher.group();
			if (candidate.length() <= MAX_MATCH_LENGTH && isLuhnValid(candidate)) {
				matches.add(new C2DataMatch(C2DataType.BANK_CARD, matcher.start(), matcher.end()));
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
		return 2;
	}

	@Override
	public int maxLookaheadLength() {
		return 2;
	}

	private boolean isLuhnValid(String candidate) {
		String digits = candidate.replace(" ", "").replace("-", "");
		if (digits.length() < 12 || digits.length() > 19) {
			return false;
		}
		int sum = 0;
		boolean doubleDigit = false;
		for (int i = digits.length() - 1; i >= 0; i--) {
			int digit = digits.charAt(i) - '0';
			if (doubleDigit) {
				digit *= 2;
				if (digit > 9) {
					digit -= 9;
				}
			}
			sum += digit;
			doubleDigit = !doubleDigit;
		}
		return sum % 10 == 0;
	}

}
