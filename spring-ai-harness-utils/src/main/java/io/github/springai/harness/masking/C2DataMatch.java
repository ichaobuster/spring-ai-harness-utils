package io.github.springai.harness.masking;

/**
 * A sensitive-data span. The original value is deliberately not exposed.
 *
 * @param type sensitive data category
 * @param start inclusive character offset
 * @param end exclusive character offset
 */
public record C2DataMatch(C2DataType type, int start, int end) {

	public C2DataMatch {
		if (type == null) {
			throw new IllegalArgumentException("type must not be null");
		}
		if (start < 0 || end <= start) {
			throw new IllegalArgumentException("match range must be non-empty and non-negative");
		}
	}

	public int length() {
		return this.end - this.start;
	}

}
