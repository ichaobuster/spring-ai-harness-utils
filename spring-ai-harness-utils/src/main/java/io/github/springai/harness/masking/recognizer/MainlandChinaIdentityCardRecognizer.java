package io.github.springai.harness.masking.recognizer;

import io.github.springai.harness.masking.C2DataMatch;
import io.github.springai.harness.masking.C2DataRecognizer;
import io.github.springai.harness.masking.C2DataType;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects checksum-valid 18-digit mainland China identity card numbers.
 */
public final class MainlandChinaIdentityCardRecognizer implements C2DataRecognizer {

	private static final int MAX_MATCH_LENGTH = 18;

	private static final Pattern PATTERN = Pattern.compile(
			"(?<![0-9A-Za-z])([1-9]\\d{5}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx])(?![0-9A-Za-z])");

	private static final int[] WEIGHTS = { 7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2 };

	private static final char[] CHECK_CODES = { '1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2' };

	private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("uuuuMMdd")
			.withResolverStyle(ResolverStyle.STRICT);

	@Override
	public List<C2DataMatch> detect(String text) {
		if (text == null || text.isEmpty()) {
			return List.of();
		}
		List<C2DataMatch> matches = new ArrayList<>();
		Matcher matcher = PATTERN.matcher(text);
		while (matcher.find()) {
			if (isValid(matcher.group(1))) {
				matches.add(new C2DataMatch(C2DataType.ID_CARD, matcher.start(1), matcher.end(1)));
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

	private boolean isValid(String identityCard) {
		try {
			LocalDate birthDate = LocalDate.parse(identityCard.substring(6, 14), DATE_FORMATTER);
			if (birthDate.isAfter(LocalDate.now())) {
				return false;
			}
		}
		catch (DateTimeException ex) {
			return false;
		}

		int sum = 0;
		for (int i = 0; i < WEIGHTS.length; i++) {
			sum += (identityCard.charAt(i) - '0') * WEIGHTS[i];
		}
		return Character.toUpperCase(identityCard.charAt(17)) == CHECK_CODES[sum % 11];
	}

}
