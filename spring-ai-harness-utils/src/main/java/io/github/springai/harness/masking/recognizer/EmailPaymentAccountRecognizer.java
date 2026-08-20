package io.github.springai.harness.masking.recognizer;

import io.github.springai.harness.masking.C2DataMatch;
import io.github.springai.harness.masking.C2DataRecognizer;
import io.github.springai.harness.masking.C2DataType;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects email-form payment accounts.
 */
public final class EmailPaymentAccountRecognizer implements C2DataRecognizer {

	private static final int MAX_MATCH_LENGTH = 254;

	private static final Pattern PATTERN = Pattern.compile(
			"(?<![A-Za-z0-9.!#$%&'*+/=?^_`{|}~-])([A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64})@([A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+)(?![A-Za-z0-9-])");

	@Override
	public List<C2DataMatch> detect(String text) {
		if (text == null || text.isEmpty()) {
			return List.of();
		}
		List<C2DataMatch> matches = new ArrayList<>();
		Matcher matcher = PATTERN.matcher(text);
		while (matcher.find()) {
			if (matcher.group().length() <= MAX_MATCH_LENGTH) {
				matches.add(new C2DataMatch(C2DataType.PAYMENT_ACCOUNT, matcher.start(), matcher.end()));
			}
		}
		return List.copyOf(matches);
	}

	@Override
	public int maxMatchLength() {
		return MAX_MATCH_LENGTH;
	}

}
