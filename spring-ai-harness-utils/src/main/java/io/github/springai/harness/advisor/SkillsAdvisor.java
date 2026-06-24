package io.github.springai.harness.advisor;

import lombok.extern.slf4j.Slf4j;
import io.github.springai.harness.tool.SkillsTool;
import io.github.springai.harness.tool.SkillsTool.Skill;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.util.json.JsonParser;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * An advisor that dynamically adds allowed tools to the chat options based on the
 * activated skills in the conversation history.
 *
 * @author ichaobuster
 */
@Slf4j
public class SkillsAdvisor implements BaseAdvisor {

	public static final String TOOL_DESCRIPTION_TEMPLATE = """
			Execute a skill within the main conversation

			<skills_instructions>
			When users ask you to perform tasks, check if any of the available skills below can help complete the task more effectively. Skills provide specialized capabilities and domain knowledge.

			How to use skills:
			- Invoke skills using this tool with the skill name only (no arguments)
			- When you invoke a skill, you will see <command-message>The "{name}" skill is loading</command-message>
			- The skill's prompt will expand and provide detailed instructions on how to complete the task

			NOTE: Response always starts start with the base directory of the skill execution environment. You can use this to retrieve additional files of call shell commands.
			Skill description follows after the base directory line.

			Important:
			- Only use skills listed in <available_skills> below
			- Do not invoke a skill that is already running
			</skills_instructions>

			<available_skills>
			%s
			</available_skills>
			""";

	public static final String SKILLS_TOOL_NAME = "Skill";

	public static final String TOOL_CALLS_FIELD = "tool-calls";

	public static final String ACTIVED_SKILLS_KEY = "actived_skills";

	private final List<Skill> skills;

	private final Map<String, Skill> skillsMap;

	private final String skillsXml;

	private final Map<String, ToolCallback> toolRegistry;

	private final int order;

	private SkillsAdvisor(int order, List<Skill> skills, Map<String, ToolCallback> toolRegistry) {
		this.order = order;
		this.skills = skills;
		this.toolRegistry = toolRegistry;

		this.skillsMap = new HashMap<>();
		for (Skill skill : this.skills) {
			skillsMap.put(skill.name(), skill);
		}

		this.skillsXml = this.skills.stream().map(s -> s.toXml()).collect(Collectors.joining("\n"));
	}

	@Override
	public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
		if (request.prompt() == null || request.prompt().getInstructions() == null) {
			return request;
		}

		if (request.prompt().getOptions() instanceof ToolCallingChatOptions toolOptions) {
			// 需要直接注入到 request 的 options 中而不是创建 copy，因为 ToolCallAdvisor 中使用 tool 时直接调用的原始 request 的 copy
			List<ToolCallback> toolCallbacks = toolOptions.getToolCallbacks();
			// 注入 SkillsTool
			Set<String> existingNames = toolCallbacks.stream()
					.map(tc -> tc.getToolDefinition().name())
					.collect(Collectors.toSet());
			if (!existingNames.contains(SKILLS_TOOL_NAME)) {
				toolCallbacks.add(getSkillsToolCallback());
			}

			Set<String> activeSkills = new HashSet<>();

			// 找出所有被调用的 Skill
			for (Message message : request.prompt().getInstructions()) {
				if (message instanceof AssistantMessage assistantMessage) {
					if (assistantMessage.getToolCalls() == null) {
						continue;
					}
					for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
						if (!SKILLS_TOOL_NAME.equals(toolCall.name())) {
							continue;
						}
						String arguments = toolCall.arguments();
						if (arguments != null) {
							SkillsTool.SkillsInput skillsInput = JsonParser.fromJson(arguments, SkillsTool.SkillsInput.class);
							activeSkills.add(skillsInput.command());
						}
					}
				}
			}

			Set<String> skillsInContext = getSkillsContext(request);
			if (activeSkills.isEmpty() || skillsInContext.containsAll(activeSkills)) {
				return request;
			}

			// 找出所有 Skill 中声明需要注入的工具
			Set<String> springAiToolNames = new HashSet<>();
			for (String skillName : activeSkills) {
				Skill skill = skillsMap.get(skillName);
				if (skill != null) {
					List<String> springAiToolFieldValues = new ArrayList<>();
					Object springAiToolObj = skill.frontMatter().get(TOOL_CALLS_FIELD);
					if (springAiToolObj instanceof List) {
						springAiToolFieldValues.addAll((List<String>) springAiToolObj);
					} else if (springAiToolObj instanceof String springAiToolFieldText) {
						springAiToolFieldValues.add(springAiToolFieldText);
					}
					for (String springAiToolFieldValue : springAiToolFieldValues) {
						if (springAiToolFieldValue != null && !springAiToolFieldValue.isBlank()) {
							springAiToolNames.addAll(Arrays.asList(springAiToolFieldValue.trim().split("\\s+")));
						}
					}
				}
			}

			if (springAiToolNames.isEmpty()) {
				return request;
			}

			// 注入工具
			for (String springAiToolName : springAiToolNames) {
				if (!existingNames.contains(springAiToolName) && toolRegistry.containsKey(springAiToolName)) {
					log.info("Add Spring AI tool to chat options: {}", springAiToolName);
					toolCallbacks.add(toolRegistry.get(springAiToolName));
					existingNames.add(springAiToolName);
				}
			}

			// 更新 context
			skillsInContext.addAll(activeSkills);
		}

		return request;
	}

	public ToolCallback getSkillsToolCallback() {
		return FunctionToolCallback.builder(SKILLS_TOOL_NAME, new SkillsTool.SkillsFunction(skillsMap))
				.description(TOOL_DESCRIPTION_TEMPLATE.formatted(skillsXml))
				.inputType(SkillsTool.SkillsInput.class)
				.build();
	}

	/**
	 * 从 context 获取 activeSkills
	 *
	 * @param request
	 */
	public Set<String> getSkillsContext(ChatClientRequest request) {
		Object activeSkillsObj = request.context().get(SkillsAdvisor.ACTIVED_SKILLS_KEY);
		if (activeSkillsObj != null && activeSkillsObj instanceof Set activeSkills) {
			return activeSkills;
		}
		return new HashSet<>();
	}

	@Override
	public ChatClientResponse after(ChatClientResponse advisedResponse, AdvisorChain chain) {
		return advisedResponse;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	public static Builder builder() {
		return new Builder();
	}

	public Consumer<ChatClient.AdvisorSpec> advisorSpecConsumer() {
		return advisorSpec -> {
			advisorSpec.advisors(this);
			advisorSpec.param(ACTIVED_SKILLS_KEY, new HashSet<>());
		};
	}

	public static class Builder {

		// After the default DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER
		private int order = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 50;

		private List<Skill> skills = new ArrayList<>();

		private Map<String, ToolCallback> toolRegistry = new HashMap<>();

		public Builder order(int order) {
			this.order = order;
			return this;
		}

		public Builder skills(List<Skill> skills) {
			this.skills.addAll(skills);
			return this;
		}

		public Builder toolRegistry(List<ToolCallback> toolRegistry) {
			for (ToolCallback toolCallback : toolRegistry) {
				this.toolRegistry.put(toolCallback.getToolDefinition().name(), toolCallback);
			}
			return this;
		}

		public SkillsAdvisor build() {
			Assert.notNull(this.skills, "skills cannot be null");
			Assert.notNull(this.toolRegistry, "toolRegistry cannot be null");

			return new SkillsAdvisor(this.order, this.skills, this.toolRegistry);
		}

	}

}
