package io.github.springai.harness.auth;

/**
 * Exception thrown when authentication or authorization header parsing fails.
 *
 * @author buyc
 */
public class AuthenticationException extends RuntimeException {

	public AuthenticationException(String message) {
		super(message);
	}

	public AuthenticationException(String message, Throwable cause) {
		super(message, cause);
	}
}
