package io.github.springai.harness.dto;

/**
 * DTO representing workspace identity information for admin APIs.
 *
 * @param workspaceId combined identity key (e.g. system-agent-user)
 * @param system      system identifier
 * @param agent       agent identifier
 * @param user        user identifier
 * @param prefix      full storage prefix path in OSS
 * @author ichaobuster
 */
public record WorkspaceInfoDto(
		String workspaceId,
		String system,
		String agent,
		String user,
		String prefix
) {
}
