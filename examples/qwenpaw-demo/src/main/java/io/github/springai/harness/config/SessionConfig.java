package io.github.springai.harness.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.HashSet;
import java.util.Set;

/**
 * SessionConfig
 *
 * @author ichaobuster
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionConfig {

	public static final String FILE_NAME_TEMPLATE = "config_session_%s.json";

	/**
	 * 智能体ID
	 */
	private String agentId;

	/**
	 * 会话ID
	 */
	private String conversationId;

	private Set<String> allowedTools = new HashSet<>();

	public SessionConfig() {
	}

	public SessionConfig(String agentId, String conversationId) {
		this();
		this.agentId = agentId;
		this.conversationId = conversationId;
	}
}
