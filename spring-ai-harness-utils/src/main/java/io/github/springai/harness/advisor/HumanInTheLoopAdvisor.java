package io.github.springai.harness.advisor;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.util.json.JsonParser;
import org.springframework.util.Assert;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * An advisor that intercepts tool calls and requires human-in-the-loop (HITL)
 * confirmation before execution.
 * <p>
 * For configured tools, it wraps the original tool callback with a proxy that returns a
 * specific HITL-required payload and sets {@code returnDirect=true}. This causes the
 * Spring AI execution loop to break and return control to the application, which can then
 * asynchronously seek human approval.
 *
 * @author ichaobuster
 */
@Slf4j
public class HumanInTheLoopAdvisor implements BaseChatMemoryAdvisor {

    public static final String HITL_RESPONSE_KEY = "hitl_response";

    public static final String HITL_ALWAYS_ALLOW_TOOLS_KEY = "hitl_always_allow_tools";

    private final ChatMemory chatMemory;

    private final int order;

    private final Set<String> needPermissionTools;

    private final ToolCallingManager toolCallingManager;

    private HumanInTheLoopAdvisor(ChatMemory chatMemory, int order, Set<String> needPermissionTools, ToolCallingManager toolCallingManager) {
        Assert.notNull(chatMemory, "chatMemory cannot be null");
        Assert.notNull(needPermissionTools, "hitlToolNames should not be null");
        Assert.notNull(toolCallingManager, "toolCallingManager should not be null");
        this.chatMemory = chatMemory;
        this.order = order;
        this.needPermissionTools = new HashSet<>(needPermissionTools);
        this.toolCallingManager = toolCallingManager;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        if (!(chatClientRequest.prompt().getOptions() instanceof ToolCallingChatOptions)) {
            return chatClientRequest;
        }
        ToolCallingChatOptions toolOptions = (ToolCallingChatOptions) chatClientRequest.prompt().getOptions();
        List<ToolCallback> toolCallbacks = toolOptions.getToolCallbacks();

        List<Message> messages = chatClientRequest.prompt().getInstructions();

        Object hitlResponseObj = chatClientRequest.context().get(HITL_RESPONSE_KEY);
        if (hitlResponseObj != null && hitlResponseObj instanceof HitlResponse hitlResponse && !hitlResponse.isToolCalled()) {
            if (messages.size() > 0) {
                Message lastMessage = messages.get(messages.size() - 1);
                Prompt toolCallPrompt = chatClientRequest.prompt();
                if (lastMessage instanceof AssistantMessage assistantMessage && assistantMessage.hasToolCalls()) {
                    String conversationId = getConversationId(chatClientRequest.context());
                    if (Permission.DENY == hitlResponse.getPermission()) {
                        // DENY 包装成拒绝调用情况
                        List<ToolCallback> toolCallbacksCopy = toolCallbacks.stream().map(tc ->
                                        tc.getToolDefinition().name().equals(hitlResponse.getRequest().getTool()) ? wrapAsUserDenyTool(tc) : tc
                                )
                                .collect(Collectors.toList());
                        ToolCallingChatOptions optionsCopy = toolOptions.copy();
                        optionsCopy.setToolCallbacks(toolCallbacksCopy);
                        toolCallPrompt = toolCallPrompt.mutate().chatOptions(optionsCopy).build();
                    }

                    ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(
                            toolCallPrompt,
                            ChatResponse.builder().generations(List.of(new Generation(assistantMessage))).build()
                    );
                    List<Message> conversationHistory = toolExecutionResult.conversationHistory();
                    Message toolResponse = conversationHistory.get(conversationHistory.size() - 1);
                    messages.add(toolResponse);
                    this.chatMemory.add(conversationId, toolResponse);
                    // 更新到 context
                    hitlResponse.setToolCalled(true);
                }
            }
        }

        // 筛选最终需要 HITL 的工具
        Set<String> needPermissionToolsInSession = new HashSet<>(this.needPermissionTools);
        Object alwaysAllowToolsObj = chatClientRequest.context().get(HITL_ALWAYS_ALLOW_TOOLS_KEY);
        if (alwaysAllowToolsObj != null && alwaysAllowToolsObj instanceof Set alwaysAllowTools) {
            needPermissionToolsInSession.removeAll(alwaysAllowTools);
        }

        toolCallbacks.replaceAll(tc -> {
            String toolName = tc.getToolDefinition().name();
            if (needPermissionToolsInSession.contains(toolName)) {
                return wrapWithHitlInterceptor(tc);
            }
            return tc;
        });

        return chatClientRequest;
    }

    private ToolCallback wrapWithHitlInterceptor(ToolCallback toolCallback) {
        String toolName = toolCallback.getToolDefinition().name();

        return FunctionToolCallback.builder(toolName, (Function<Map<String, Object>, String>) (args) ->
                        JsonParser.toJson(new HitlRequest(true, toolName, args, null))
                )
                .description(toolCallback.getToolDefinition().description())
                .inputSchema(toolCallback.getToolDefinition().inputSchema())
                .inputType(Map.class)
                .toolMetadata(ToolMetadata.builder().returnDirect(true).build())
                .build();
    }

    private ToolCallback wrapAsUserDenyTool(ToolCallback toolCallback) {
        return FunctionToolCallback.builder(toolCallback.getToolDefinition().name(), (Function<Map<String, Object>, String>) (args) ->
                        "User denied permission to execute this tool."
                )
                .description(toolCallback.getToolDefinition().description())
                .inputSchema(toolCallback.getToolDefinition().inputSchema())
                .inputType(Map.class)
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain advisorChain) {
        return response;
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    public static Builder builder(ChatMemory chatMemory) {
        return new Builder(chatMemory);
    }

    public static final class Builder {

        // After the default ChatMemoryAdvisor which is at Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER
        private int order = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 100;

        private final Set<String> needPermissionTools = new HashSet<>();

        private ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();

        private final ChatMemory chatMemory;

        private Builder(ChatMemory chatMemory) {
            Assert.notNull(chatMemory, "chatMemory cannot be null");
            this.chatMemory = chatMemory;
        }

        public Builder order(int order) {
            this.order = order;
            return this;
        }

        public Builder needPermissionTools(Set<String> tools) {
            if (tools != null) {
                this.needPermissionTools.addAll(tools);
            }
            return this;
        }

        public Builder toolCallingManager(ToolCallingManager toolCallingManager) {
            this.toolCallingManager = toolCallingManager;
            return this;
        }

        public HumanInTheLoopAdvisor build() {
            return new HumanInTheLoopAdvisor(this.chatMemory, this.order, this.needPermissionTools, this.toolCallingManager);
        }

    }
	
    @Data
    @AllArgsConstructor
    public static final class HitlRequest {
        private final boolean hitlRequired;
        private final String tool;
        private final Map<String, Object> args;
        private final String toolCallId;
    }

    @Data
    public static final class HitlResponse {
        private final HitlRequest request;
        private final Permission permission;
        private boolean toolCalled = false;

        public HitlResponse(HitlRequest request, Permission permission) {
            this.request = request;
            this.permission = permission;
        }

    }

    public enum Permission {
        ALLOW_ONCE,
        ALLOW_ALWAYS,
        DENY
    }

}
