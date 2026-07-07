package io.github.springai.harness.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AuthenticationException}.
 */
@DisplayName("AuthenticationException Unit Tests")
class AuthenticationExceptionTest {

	@Test
	@DisplayName("Should create exception with message")
	void shouldCreateExceptionWithMessage() {
		AuthenticationException exception = new AuthenticationException("Auth failed");

		assertThat(exception.getMessage()).isEqualTo("Auth failed");
		assertThat(exception.getCause()).isNull();
	}

	@Test
	@DisplayName("Should create exception with message and cause")
	void shouldCreateExceptionWithMessageAndCause() {
		RuntimeException cause = new RuntimeException("DB Connection timeout");
		AuthenticationException exception = new AuthenticationException("Auth failed due to internal error", cause);

		assertThat(exception.getMessage()).isEqualTo("Auth failed due to internal error");
		assertThat(exception.getCause()).isEqualTo(cause);
	}
}
