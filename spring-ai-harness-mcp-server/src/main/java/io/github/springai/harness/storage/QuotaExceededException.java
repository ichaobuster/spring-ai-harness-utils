package io.github.springai.harness.storage;

import lombok.Getter;

/**
 * Exception thrown when the workspace storage quota is exceeded.
 *
 * @author ichaobuster
 */
@Getter
public class QuotaExceededException extends RuntimeException {

	private final long usedBytes;

	private final long maxBytes;

	private final long requiredBytes;

	public QuotaExceededException(String message, long usedBytes, long maxBytes, long requiredBytes) {
		super(message);
		this.usedBytes = usedBytes;
		this.maxBytes = maxBytes;
		this.requiredBytes = requiredBytes;
	}

}
