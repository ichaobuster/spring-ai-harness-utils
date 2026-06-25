package io.github.springai.harness.agent;

import io.github.springai.harness.advisor.*;
import io.github.springai.harness.chat.memory.repository.local.LocalFileChatMemoryRepository;
import io.github.springai.harness.config.AgentConfig;
import io.github.springai.harness.config.SessionConfig;
import io.github.springai.harness.mcp.AgentMcpClients;
import io.github.springai.harness.sandbox.LazyLoadSandboxCreator;
import io.github.springai.harness.sandbox.LazyLoadSandboxToolsService;
import io.github.springai.harness.task.AgentTaskService;
import io.github.springai.harness.task.AgentTaskTools;
import io.github.springai.harness.tool.SkillsTool;
import io.github.springai.harness.util.FileSystemConfigUtil;
import io.github.springai.harness.util.ResourceUtil;
import io.github.springai.harness.workspace.AgentWorkspace;
import io.github.springai.harness.storage.StorageProvider;
import io.github.springai.harness.tool.DateTimeTools;
import io.github.springai.harness.tool.StorageProviderTools;
import io.github.springai.harness.tool.ToolResultBudgetTool;
import io.github.springai.harness.util.SkillUtil;
import io.modelcontextprotocol.client.McpSyncClient;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.content.Media;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * HarnessAgent
 *
 * @author ichaobuster
 */
@Slf4j
@Component
public class HarnessAgents {

	private static final String DEFAULT_SYS_PROMPT = "You are a helpful assistant.";

	private static final List<String> DEFAULT_PROMPT_FILES = List.of("AGENTS.md", "SOUL.md", "PROFILE.md");

	private static final Set<String> RESULT_COMPACTABLE_TOOL_NAMES = Set.of("Read", "Edit", "Glob", "Grep", "MemoryView", "MemoryStrReplace", "run_shell_command", "run_ipython_cell");

	private static final String SESSIONS_SUB_DIR = "sessions";

	private static final int SESSION_MAX_MESSAGES = 200;

	private static final String SKILLS_SUB_DIR = "skills";

	private static final String MEMORIES_SUB_DIR = "memories";

	private final AgentWorkspace agentWorkspace;

	private final StringRedisTemplate stringRedisTemplate;

	private final AgentTaskService agentTaskService;

	private final AgentMcpClients agentMcpClients;

	private final LazyLoadSandboxCreator sandboxCreator;

	private final ChatModel chatModel;

	private final ChatClient chatClient;

	public HarnessAgents(
			@Autowired AgentWorkspace agentWorkspace,
			@Autowired ChatModel chatModel,
			@Autowired StringRedisTemplate stringRedisTemplate,
			@Autowired AgentTaskService agentTaskService,
			@Autowired AgentMcpClients agentMcpClients,
			@Autowired(required = false) LazyLoadSandboxCreator sandboxCreator
	) {
		this.agentWorkspace = agentWorkspace;
		this.chatModel = chatModel;
		this.stringRedisTemplate = stringRedisTemplate;
		this.agentTaskService = agentTaskService;
		this.agentMcpClients = agentMcpClients;
		this.sandboxCreator = sandboxCreator;
		this.chatClient = createAgent();
	}

	private ChatClient createAgent() {
		return ChatClient.builder(chatModel)
				.defaultToolCallbacks(ToolResultBudgetTool.createToolCallback(stringRedisTemplate))
				.defaultTools(
						new DateTimeTools(),
						TodoWriteTool.builder().build()
						// TODO 使用 sandbox 替换 SHELL TOOL
//						ShellTools.builder().build()
				)
				.defaultAdvisors(
						ToolCallAdvisor.builder().disableMemory().build(),
						// context compact
						ToolResultBudgetAdvisor.builder(stringRedisTemplate)
								.skipToolName(ToolResultBudgetTool.TOOL_NAME)
								.maxPerGroupBudgetChars(100_000)
								.maxSingleResultChars(5000).build(),
						MicroCompactAdvisor.builder()
								.compactableToolNames(RESULT_COMPACTABLE_TOOL_NAMES)
								.keepRecent(5)
								.triggerThreshold(10)
								.build(),
						ClearThinkingAdvisor.builder().keepRecent(2).build(),
						ToolArgsCompactAdvisor.builder()
								.maxArgLength(2000)
								.keepRecent(5)
								.triggerMessages(10)
								.build()
				)
				.build();
	}

	public Flux<ChatClientResponse> chat(AgentConfig config, String conversationId, String userText, List<Media> media, HumanInTheLoopAdvisor.HitlResponse hitlResponse) {
		return chat(config, conversationId, userText, media, hitlResponse, false);
	}

	public Flux<ChatClientResponse> chat(AgentConfig config, String conversationId, String userText, List<Media> media, HumanInTheLoopAdvisor.HitlResponse hitlResponse, boolean isSubAgent) {
		Assert.notNull(config, "config should not be null");
		Assert.hasText(conversationId, "conversationId should not be empty");
		Assert.isTrue(StringUtils.hasText(userText) || hitlResponse != null, "userText and hitlResponse should not both be null");

		// 与用户 AgentConfig 有关的内容
		StorageProvider userWorkspace = this.agentWorkspace.initUserWorkspace(config);

		ChatMemory chatMemory = createMemory(config);

		// TODO 根据配置添加工具
		List<ToolCallback> harnessToolCallbacks = List.of(ToolCallbacks.from(
				StorageProviderTools.builder(userWorkspace).build()
		));
		// TODO subagent 是否添加需要 harness 的 tools ？
//		List<ToolCallback> harnessToolCallbacks = new ArrayList<>();
//		harnessToolCallbacks.addAll(
//				HarnessToolUtil.toHarnessToolCallbacks(ToolCallbacks.from(FileSystemTools.builder().build()), userWorkspace, "filePath")
//		);
//		harnessToolCallbacks.addAll(HarnessToolUtil.toHarnessToolCallbacks(
//				ToolCallbacks.from(ListDirectoryTool.builder().workingDirectory(userWorkspace).build()), userWorkspace, "path")
//		);
//		harnessToolCallbacks.addAll(HarnessToolUtil.toHarnessToolCallbacks(
//				ToolCallbacks.from(GlobTool.builder().workingDirectory(userWorkspace).build()), userWorkspace, "path")
//		);
//		harnessToolCallbacks.addAll(HarnessToolUtil.toHarnessToolCallbacks(
//				ToolCallbacks.from(GrepTool.builder()
//						.workingDirectory(userWorkspace)
//						.maxOutputLength(10_000)
//						.maxLineLength(1000)
//						.build()), userWorkspace, "path")
//		);
		// TODO 添加 AskUserQuestionTool？

		// 添加沙箱工具
		LazyLoadSandboxToolsService sandboxService;
		List<ToolCallback> sandboxToolCallbacks = new ArrayList<>();
		if (this.sandboxCreator != null) {
			sandboxService = this.sandboxCreator.createToolService(config.getAgentId());
			sandboxToolCallbacks = sandboxService.getSandboxToolCallbacks();
		} else {
            sandboxService = null;
        }

        // TODO python 执行用沙箱还是用 PythonService

		// SubAgent 不能使用的工具
		List<ToolCallback> toolsSubAgentNoPermission = new ArrayList<>();
		if (!isSubAgent) {
			toolsSubAgentNoPermission.addAll(List.of(ToolCallbacks.from(new AgentTaskTools(config.getAgentId(), this.agentTaskService, this.agentWorkspace))));
		}

		// MCP 处理
		List<McpSyncClient> mcpSyncClients = this.agentMcpClients.getMcpSyncClients(config);
		List<ToolCallback> mcpToolCallbacks = McpToolUtils.getToolCallbacksFromSyncClients(mcpSyncClients);

		Set<String> alwaysAllowTools = getAlwaysAllowTools(userWorkspace, config, conversationId, hitlResponse);

		SkillsAdvisor skillsAdvisor = getSkillsAdvisor(userWorkspace);

		return this.chatClient.prompt()
				.options(OpenAiChatOptions.builder()
						.model(config.getModel())
						.build())
				.system(createSystemMessage(userWorkspace, config))
				// autoCompactAdvisor 需使用 Consumer
				.advisors(AutoCompactAdvisor.builder(chatModel)
						.contextWindow(config.getContextWindow())
						.maxOutputTokens(config.getMaxOutputTokens())
						.autoCompactBufferTokens(config.getMaxOutputTokens())
						.build()
						.advisorSpecConsumer())
				.advisors(
						// 本地 JSONL Message存储
						AdvancedMessageChatMemoryAdvisor.builder(chatMemory).useStrict(true).build(),
						// memory
						// TODO 是否添加记忆开关？根据是否开启开关决定是否增加 AutoMemoryToolsAdvisor ？
						// TODO 使用 OSS Provider？
						AutoMemoryToolsAdvisor.builder()
								.memoryStorage(userWorkspace.subDirProvider(MEMORIES_SUB_DIR))
								.build(),
						// human-in-the-loop
						HumanInTheLoopAdvisor.builder(chatMemory)
								.needPermissionTools(config.getNeedPermissionTools())
								.build()
						// dream
						// TODO dream 目前编码质量存在问题
//						AutoDreamToolsAdvisor.builder()
//								.memoryStorage(LocalFileStorage.builder()
//										.baseDir(workspaceDir)
//										.build())
//								.chatModel(chatModel)
//								.build()
				)
				// 添加 harness 工具
				.toolCallbacks(harnessToolCallbacks)
				// 添加 mcp 工具
				.toolCallbacks(mcpToolCallbacks)
				// 添加沙箱工具
				.toolCallbacks(sandboxToolCallbacks)
				// 添加非 SubAgent 可用的工具
				.toolCallbacks(toolsSubAgentNoPermission)
				// 保证 skill 动态加载
				.advisors(skillsAdvisor.advisorSpecConsumer())
				// 加载会话
				.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
				.user(u -> {
					if (!CollectionUtils.isEmpty(media)) u.media(media.toArray(Media[]::new));
				})
				.user(u -> {
					if (StringUtils.hasText(userText)) u.text(userText);
				})
				// HITL context 处理
				.advisors(a -> {
					if (hitlResponse != null) a.param(HumanInTheLoopAdvisor.HITL_RESPONSE_KEY, hitlResponse);
				})
				.advisors(a -> a.param(HumanInTheLoopAdvisor.HITL_ALWAYS_ALLOW_TOOLS_KEY, alwaysAllowTools))
				.stream()
				.chatClientResponse()
				.doFinally(signal -> {
					agentMcpClients.closeMcpSyncClients(mcpSyncClients);
					if (sandboxService != null) {
						sandboxService.closeMcpClient();
					}
				});
	}


	private Set<String> getAlwaysAllowTools(StorageProvider userWorkspace, AgentConfig config, String conversationId, HumanInTheLoopAdvisor.HitlResponse hitlResponse) {
		if (hitlResponse == null) {
			return new HashSet<>();
		}

		String sessionFileName = SESSIONS_SUB_DIR + "/" + SessionConfig.FILE_NAME_TEMPLATE.formatted(conversationId);
		SessionConfig sessionConfig = FileSystemConfigUtil.loadFromFile(
				userWorkspace,
				sessionFileName,
				SessionConfig.class,
				new SessionConfig(config.getAgentId(), conversationId)
		);
		Set<String> allowedInSessionTools = new HashSet<>(sessionConfig.getAllowedTools());
		if (hitlResponse.getPermission() == HumanInTheLoopAdvisor.Permission.ALLOW_ALWAYS && hitlResponse.getRequest() != null && StringUtils.hasText(hitlResponse.getRequest().getTool())) {
			allowedInSessionTools.add(hitlResponse.getRequest().getTool());
			FileSystemConfigUtil.writeConfigIntoFile(userWorkspace, sessionFileName, sessionConfig);
		}
		return allowedInSessionTools;
	}

	public void removeSession(AgentConfig config, String conversationId) {
		// 删除对话记录
		ChatMemory chatMemory = createMemory(config);
		chatMemory.clear(conversationId);
		// 删除 session config 文件
		try {
			StorageProvider userWorkspace = this.agentWorkspace.initUserWorkspace(config);
			userWorkspace.delete(SESSIONS_SUB_DIR + "/" + SessionConfig.FILE_NAME_TEMPLATE.formatted(conversationId));
		} catch (IOException e) {
			log.error("Failed to remove session config file.", e);
		}
	}

	private static SkillsAdvisor getSkillsAdvisor(StorageProvider userWorkspace) {
		List<SkillsTool.Skill> skills = SkillUtil.loadStorageProvider(userWorkspace, SKILLS_SUB_DIR);
		return SkillsAdvisor.builder()
				.skills(skills)
				.build();
	}

	/**
	 * 读取 prompt 文件组装 system prompt
	 * TODO 追加其他内容？
	 */
	private String createSystemMessage(StorageProvider userWorkspace, AgentConfig config) {
		String systemMessage = DEFAULT_PROMPT_FILES.stream()
				.map(partName -> readSystemPromptPart(userWorkspace, partName, true))
				.collect(Collectors.joining("\n\n"));
		if (systemMessage.trim().isBlank()) {
			systemMessage = DEFAULT_SYS_PROMPT;
		}
		return "Your workspace is %s\n\n".formatted(userWorkspace.toString()) + systemMessage;
	}


	private String readSystemPromptPart(StorageProvider userWorkspace, String partName, boolean createIfAbsent) {
		if (userWorkspace.exists(partName)) {
			try {
				return userWorkspace.readString(partName);
			} catch (IOException e) {
				log.error("Failed to read " + partName + " in user workspace", e);
			}
		}
		ClassPathResource promptPartResource = new ClassPathResource("prompt/" + partName);
		String defaultPromptPart = ResourceUtil.loadResourceAsString(promptPartResource);
		if (createIfAbsent) {
			try {
				userWorkspace.writeString(partName, defaultPromptPart);
			} catch (IOException e) {
				log.error("Failed to create " + partName, e);
			}
		}
		return defaultPromptPart;
	}

	private ChatMemory createMemory(AgentConfig config) {
		StorageProvider userWorkspace = this.agentWorkspace.initUserWorkspace(config);
		ChatMemoryRepository chatMemoryRepository = LocalFileChatMemoryRepository.builder(userWorkspace.subDirProvider(SESSIONS_SUB_DIR))
				.build();
		return MessageWindowChatMemory.builder()
				.chatMemoryRepository(chatMemoryRepository)
				.maxMessages(SESSION_MAX_MESSAGES)
				.build();
	}

}
