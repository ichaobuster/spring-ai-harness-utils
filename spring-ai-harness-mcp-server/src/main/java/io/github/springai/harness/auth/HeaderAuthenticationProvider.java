package io.github.springai.harness.auth;

import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.function.ServerRequest;

import java.util.Arrays;

/**
 * Header-based AuthenticationProvider implementation.
 * Resolves system, agent, and user identity from the Authorization header.
 *
 * @author buyc
 */
public class HeaderAuthenticationProvider implements AuthenticationProvider {

	private static final String AUTHORIZATION_HEADER = "Authorization";

	@Override
	public WorkspaceIdentity authenticate(ServerRequest serverRequest) throws AuthenticationException {
		if (serverRequest == null || serverRequest.headers() == null || CollectionUtils.isEmpty(serverRequest.headers().header(AUTHORIZATION_HEADER))) {
			throw new AuthenticationException("Missing Authorization header");
		}

		String authKey = serverRequest.headers().firstHeader(AUTHORIZATION_HEADER);
		if (!StringUtils.hasText(authKey)) {
			throw new AuthenticationException("Authorization header is empty");
		}

		String token = authKey.trim();
		if (token.startsWith("Bearer ") || token.startsWith("bearer ")) {
			token = token.substring(7).trim();
		}

		// Support hyphen separated: system-agent-user or slash separated: system/agent/user
		String[] parts = token.contains("/") ? token.split("/") : token.split("-");
		if (parts.length != 3 || Arrays.stream(parts).anyMatch(String::isBlank)) {
			throw new AuthenticationException("Authorization header format error");
		}

		return new WorkspaceIdentity(parts[0].trim(), parts[1].trim(), parts[2].trim());
	}
}
