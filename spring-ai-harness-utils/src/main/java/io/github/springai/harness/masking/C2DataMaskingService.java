package io.github.springai.harness.masking;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import io.github.springai.harness.masking.recognizer.BankCardRecognizer;
import io.github.springai.harness.masking.recognizer.EmailPaymentAccountRecognizer;
import io.github.springai.harness.masking.recognizer.MainlandChinaIdentityCardRecognizer;
import io.github.springai.harness.masking.recognizer.PhoneNumberRecognizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Detects and masks common C2 sensitive data in unstructured text.
 */
public final class C2DataMaskingService {

	public static final char DEFAULT_MASK_CHARACTER = '*';

	public static final String DEFAULT_PHONE_REGION = "CN";

	private final char maskCharacter;

	private final String defaultPhoneRegion;

	private final PhoneNumberUtil phoneNumberUtil;

	private final List<C2DataRecognizer> recognizers;

	private final int maxMatchLength;

	private C2DataMaskingService(char maskCharacter, String defaultPhoneRegion,
			List<C2DataRecognizer> recognizers) {
		if (defaultPhoneRegion == null || defaultPhoneRegion.isBlank()) {
			throw new IllegalArgumentException("defaultPhoneRegion must not be blank");
		}
		this.maskCharacter = maskCharacter;
		this.defaultPhoneRegion = defaultPhoneRegion.toUpperCase(Locale.ROOT);
		this.phoneNumberUtil = PhoneNumberUtil.getInstance();
		this.recognizers = List.copyOf(recognizers);
		this.maxMatchLength = this.recognizers.stream()
				.mapToInt(C2DataRecognizer::maxMatchLength)
				.peek(length -> {
					if (length <= 0) {
						throw new IllegalArgumentException("recognizer maxMatchLength must be positive");
					}
				})
				.max()
				.orElse(0);
	}

	public static Builder builder() {
		return new Builder();
	}

	public char getMaskCharacter() {
		return this.maskCharacter;
	}

	public String getDefaultPhoneRegion() {
		return this.defaultPhoneRegion;
	}

	public int getMaxMatchLength() {
		return this.maxMatchLength;
	}

	/**
	 * Finds supported C2 data without returning the original values.
	 */
	public List<C2DataMatch> detect(String text) {
		if (text == null || text.isEmpty()) {
			return List.of();
		}

		List<C2DataMatch> candidates = new ArrayList<>();
		for (C2DataRecognizer recognizer : this.recognizers) {
			List<C2DataMatch> matches = recognizer.detect(text);
			if (matches != null) {
				matches.stream().filter(match -> match != null && match.end() <= text.length()).forEach(candidates::add);
			}
		}
		return resolveOverlaps(candidates);
	}

	/**
	 * Masks all detected C2 data while preserving text length and separators.
	 */
	public String mask(String text) {
		if (text == null || text.isEmpty()) {
			return text;
		}
		List<C2DataMatch> matches = detect(text);
		if (matches.isEmpty()) {
			return text;
		}

		char[] masked = text.toCharArray();
		for (C2DataMatch match : matches) {
			applyMask(text, masked, match);
		}
		return new String(masked);
	}

	/**
	 * Creates isolated state for masking one streaming message.
	 */
	public StreamingMaskingSession newStreamingSession() {
		return new StreamingMaskingSession(this);
	}

	private List<C2DataMatch> resolveOverlaps(List<C2DataMatch> candidates) {
		List<C2DataMatch> prioritized = candidates.stream()
				.sorted(Comparator.comparingInt((C2DataMatch match) -> priority(match.type())).reversed()
						.thenComparing(Comparator.comparingInt(C2DataMatch::length).reversed())
						.thenComparingInt(C2DataMatch::start))
				.toList();

		List<C2DataMatch> accepted = new ArrayList<>();
		for (C2DataMatch candidate : prioritized) {
			if (accepted.stream().noneMatch(existing -> overlaps(existing, candidate))) {
				accepted.add(candidate);
			}
		}
		accepted.sort(Comparator.comparingInt(C2DataMatch::start));
		return List.copyOf(accepted);
	}

	private int priority(C2DataType type) {
		return switch (type) {
			case ID_CARD -> 500;
			case BANK_CARD -> 400;
			case PAYMENT_ACCOUNT -> 300;
			case PHONE_NUMBER -> 200;
			case CUSTOM -> 100;
		};
	}

	private boolean overlaps(C2DataMatch left, C2DataMatch right) {
		return left.start() < right.end() && right.start() < left.end();
	}

	private void applyMask(String original, char[] masked, C2DataMatch match) {
		switch (match.type()) {
			case PHONE_NUMBER -> maskPhoneNumber(original, masked, match);
			case ID_CARD -> maskSignificantCharacters(original, masked, match.start(), match.end(), 3, 4);
			case BANK_CARD -> maskSignificantCharacters(original, masked, match.start(), match.end(), 4, 4);
			case PAYMENT_ACCOUNT -> maskEmail(original, masked, match);
			case CUSTOM -> maskSignificantCharacters(original, masked, match.start(), match.end(), 0, 0);
		}
	}

	private void maskPhoneNumber(String original, char[] masked, C2DataMatch match) {
		String raw = original.substring(match.start(), match.end());
		int countryCodeDigits = 0;
		if (raw.stripLeading().startsWith("+")) {
			try {
				PhoneNumber number = this.phoneNumberUtil.parse(raw, this.defaultPhoneRegion);
				countryCodeDigits = Integer.toString(number.getCountryCode()).length();
			}
			catch (Exception ignored) {
				countryCodeDigits = 0;
			}
		}
		maskSignificantCharacters(original, masked, match.start(), match.end(), countryCodeDigits + 3, 4);
	}

	private void maskEmail(String original, char[] masked, C2DataMatch match) {
		int at = original.indexOf('@', match.start());
		if (at < match.start() || at >= match.end()) {
			maskSignificantCharacters(original, masked, match.start(), match.end(), 0, 0);
			return;
		}
		int localLength = at - match.start();
		int keepFront = localLength > 1 ? 1 : 0;
		for (int i = match.start() + keepFront; i < at; i++) {
			masked[i] = this.maskCharacter;
		}
	}

	private void maskSignificantCharacters(String original, char[] masked, int start, int end, int requestedFront,
			int requestedBack) {
		List<Integer> significant = new ArrayList<>();
		for (int i = start; i < end; i++) {
			if (Character.isLetterOrDigit(original.charAt(i))) {
				significant.add(i);
			}
		}
		if (significant.isEmpty()) {
			return;
		}
		int keepFront = Math.min(requestedFront, Math.max(0, significant.size() - 1));
		int keepBack = Math.min(requestedBack, Math.max(0, significant.size() - keepFront - 1));
		for (int i = keepFront; i < significant.size() - keepBack; i++) {
			masked[significant.get(i)] = this.maskCharacter;
		}
	}

	public static final class StreamingMaskingSession {

		private final C2DataMaskingService service;

		private final StringBuilder pending = new StringBuilder();

		private boolean finished;

		private StreamingMaskingSession(C2DataMaskingService service) {
			this.service = service;
		}

		public String accept(String chunk) {
			if (this.finished) {
				throw new IllegalStateException("streaming masking session is already finished");
			}
			if (chunk != null) {
				this.pending.append(chunk);
			}
			int cutoff = this.pending.length() - this.service.getMaxMatchLength();
			if (cutoff <= 0) {
				return "";
			}

			String buffered = this.pending.toString();
			for (C2DataMatch match : this.service.detect(buffered)) {
				if (match.start() < cutoff && match.end() > cutoff) {
					cutoff = Math.min(cutoff, match.start());
				}
			}
			if (cutoff <= 0) {
				return "";
			}
			String safe = this.service.mask(buffered.substring(0, cutoff));
			this.pending.delete(0, cutoff);
			return safe;
		}

		public String finish() {
			if (this.finished) {
				return "";
			}
			this.finished = true;
			String result = this.service.mask(this.pending.toString());
			this.pending.setLength(0);
			return result;
		}

	}

	public static final class Builder {

		private char maskCharacter = DEFAULT_MASK_CHARACTER;

		private String defaultPhoneRegion = DEFAULT_PHONE_REGION;

		private final List<C2DataRecognizer> additionalRecognizers = new ArrayList<>();

		private List<C2DataRecognizer> configuredRecognizers;

		private Builder() {
		}

		public Builder maskCharacter(char maskCharacter) {
			this.maskCharacter = maskCharacter;
			return this;
		}

		public Builder defaultPhoneRegion(String defaultPhoneRegion) {
			this.defaultPhoneRegion = defaultPhoneRegion;
			return this;
		}

		/**
		 * Appends a recognizer to the default set, or to a set previously supplied to
		 * {@link #recognizers(List)}.
		 */
		public Builder addRecognizer(C2DataRecognizer recognizer) {
			if (recognizer == null) {
				throw new IllegalArgumentException("recognizer must not be null");
			}
			if (this.configuredRecognizers == null) {
				this.additionalRecognizers.add(recognizer);
			}
			else {
				this.configuredRecognizers.add(recognizer);
			}
			return this;
		}

		/**
		 * Replaces all default and previously added recognizers.
		 */
		public Builder recognizers(List<C2DataRecognizer> recognizers) {
			if (recognizers == null || recognizers.stream().anyMatch(recognizer -> recognizer == null)) {
				throw new IllegalArgumentException("recognizers must not contain null values");
			}
			this.configuredRecognizers = new ArrayList<>(recognizers);
			this.additionalRecognizers.clear();
			return this;
		}

		public C2DataMaskingService build() {
			List<C2DataRecognizer> effectiveRecognizers;
			if (this.configuredRecognizers == null) {
				effectiveRecognizers = defaultRecognizers();
				effectiveRecognizers.addAll(this.additionalRecognizers);
			}
			else {
				effectiveRecognizers = new ArrayList<>(this.configuredRecognizers);
			}
			return new C2DataMaskingService(this.maskCharacter, this.defaultPhoneRegion, effectiveRecognizers);
		}

		private List<C2DataRecognizer> defaultRecognizers() {
			return new ArrayList<>(List.of(new MainlandChinaIdentityCardRecognizer(), new BankCardRecognizer(),
					new EmailPaymentAccountRecognizer(), new PhoneNumberRecognizer(this.defaultPhoneRegion)));
		}

	}

}
