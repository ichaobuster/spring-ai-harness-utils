package io.github.springai.harness.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.function.ServerRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link HeaderAuthenticationProvider}.
 */
@DisplayName("HeaderAuthenticationProvider Tests")
@ExtendWith(MockitoExtension.class)
class HeaderAuthenticationProviderTest {

	@Mock
	private ServerRequest serverRequest;

	@Mock
	private ServerRequest.Headers headers;

	private HeaderAuthenticationProvider provider;

	@BeforeEach
	void setUp() {
		provider = new HeaderAuthenticationProvider();
	}

	@Test
	@DisplayName("Should throw AuthenticationException when serverRequest is null")
	void shouldThrowWhenServerRequestIsNull() {
		assertThatThrownBy(() -> provider.authenticate(null))
				.isInstanceOf(AuthenticationException.class)
				.hasMessage("Missing Authorization header");
	}

	@Test
	@DisplayName("Should throw AuthenticationException when headers are missing")
	void shouldThrowWhenHeadersAreMissing() {
		when(serverRequest.headers()).thenReturn(null);

		assertThatThrownBy(() -> provider.authenticate(serverRequest))
				.isInstanceOf(AuthenticationException.class)
				.hasMessage("Missing Authorization header");
	}

	@Test
	@DisplayName("Should throw AuthenticationException when Authorization header is missing")
	void shouldThrowWhenAuthHeaderIsMissing() {
		when(serverRequest.headers()).thenReturn(headers);
		when(headers.header("Authorization")).thenReturn(List.of());

		assertThatThrownBy(() -> provider.authenticate(serverRequest))
				.isInstanceOf(AuthenticationException.class)
				.hasMessage("Missing Authorization header");
	}

	@Test
	@DisplayName("Should throw AuthenticationException when Authorization header has invalid format")
	void shouldThrowWhenAuthHeaderFormatInvalid() {
		when(serverRequest.headers()).thenReturn(headers);
		when(headers.header("Authorization")).thenReturn(List.of("invalid-format"));
		when(headers.firstHeader("Authorization")).thenReturn("invalid-format");

		assertThatThrownBy(() -> provider.authenticate(serverRequest))
				.isInstanceOf(AuthenticationException.class)
				.hasMessage("Authorization header format error");
	}

	@Test
	@DisplayName("Should parse hyphen-separated Authorization header")
	void shouldParseHyphenSeparatedHeader() {
		when(serverRequest.headers()).thenReturn(headers);
		when(headers.header("Authorization")).thenReturn(List.of("sys1-agent2-user3"));
		when(headers.firstHeader("Authorization")).thenReturn("sys1-agent2-user3");

		WorkspaceIdentity identity = provider.authenticate(serverRequest);

		assertThat(identity.system()).isEqualTo("sys1");
		assertThat(identity.agent()).isEqualTo("agent2");
		assertThat(identity.user()).isEqualTo("user3");
	}

	@Test
	@DisplayName("Should parse slash-separated Authorization header")
	void shouldParseSlashSeparatedHeader() {
		when(serverRequest.headers()).thenReturn(headers);
		when(headers.header("Authorization")).thenReturn(List.of("sys1/agent2/user3"));
		when(headers.firstHeader("Authorization")).thenReturn("sys1/agent2/user3");

		WorkspaceIdentity identity = provider.authenticate(serverRequest);

		assertThat(identity.system()).isEqualTo("sys1");
		assertThat(identity.agent()).isEqualTo("agent2");
		assertThat(identity.user()).isEqualTo("user3");
	}

	@Test
	@DisplayName("Should strip Bearer prefix if present")
	void shouldStripBearerPrefix() {
		when(serverRequest.headers()).thenReturn(headers);
		when(headers.header("Authorization")).thenReturn(List.of("Bearer sys1/agent2/user3"));
		when(headers.firstHeader("Authorization")).thenReturn("Bearer sys1/agent2/user3");

		WorkspaceIdentity identity = provider.authenticate(serverRequest);

		assertThat(identity.system()).isEqualTo("sys1");
		assertThat(identity.agent()).isEqualTo("agent2");
		assertThat(identity.user()).isEqualTo("user3");
	}
}
