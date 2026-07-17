package io.github.springai.harness.auth;

import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Workspace identity representing system, agent, and user.
 *
 * @author ichaobuster
 */
public record WorkspaceIdentity(String system, String agent, String user) {

	public WorkspaceIdentity {
		Assert.hasText(system, "system must not be empty");
		Assert.hasText(agent, "agent must not be empty");
		Assert.hasText(user, "user must not be empty");
	}

	/**
	 * Returns the dynamic session ID representing the system, agent, and user.
	 * e.g. "system-agent-user"
	 *
	 * @return session ID string
	 */
	public String getSessionId() {
		return system + "-" + agent + "-" + user;
	}

	/**
	 * Constructs full OSS workspace path with prefix.
	 * e.g., prefix = "mcp/workspaces/", system = "sys", agent = "ag", user = "usr"
	 * -> "mcp/workspaces/sys-ag-usr/"
	 *
	 * @param prefix the OSS prefix
	 * @return workspace path
	 */
	public String getWorkspacePath(String prefix) {
		String basePrefix = StringUtils.hasText(prefix) ? (prefix.endsWith("/") ? prefix : prefix + "/") : "";
		if (basePrefix.startsWith("/")) {
			basePrefix = basePrefix.substring(1);
		}
		return basePrefix + getSessionId() + "/";
	}
}
