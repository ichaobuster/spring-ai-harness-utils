package io.github.springai.harness.config;

import io.github.springai.harness.mcp.McpConfigSpec;
import io.github.springai.harness.task.AgentTaskSpec;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.*;

/**
 * AgentConfig
 *
 * @author ichaobuster
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)

public class AgentConfig {

	public static final String FILE_NAME_TEMPLATE = "config_agent.json";

	/**
	 * Agent ID，包括 workspace 等都基于该 ID 创建
	 */
	private String agentId;

	/**
	 * 模型名
	 */
	private String model = "Qwen3.6-35B-A3B";

	private int contextWindow = 128_000;

	private int maxOutputTokens = 20_000;

	private Set<String> needPermissionTools = new HashSet<>();

	private List<AgentTaskSpec.OneTimeTask> oneTimeTasks = new ArrayList<>();

	private List<AgentTaskSpec.CronTask> cronTasks = new ArrayList<>();

	private Map<String, McpConfigSpec.McpServerConfig> mcpServers = new HashMap<>();

	public AgentConfig() {
		// 默认添加 needPermissionTools
		this.needPermissionTools.add("Bash");
		this.needPermissionTools.add("run_shell_command");
	}

	public AgentConfig(String agentId) {
		this();
		this.agentId = agentId;
	}
}
