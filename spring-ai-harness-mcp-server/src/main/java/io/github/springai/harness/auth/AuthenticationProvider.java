package io.github.springai.harness.auth;

import org.springframework.web.servlet.function.ServerRequest;

/**
 * Authentication provider interface to resolve WorkspaceIdentity from ServerRequest.
 *
 * @author ichaobuster
 */
public interface AuthenticationProvider {

	/**
	 * Authenticates and extracts WorkspaceIdentity from ServerRequest.
	 *
	 * @param serverRequest current HTTP server request
	 * @return WorkspaceIdentity containing system, agent, and user
	 * @throws AuthenticationException if authentication fails
	 */
	WorkspaceIdentity authenticate(ServerRequest serverRequest) throws AuthenticationException;
}
