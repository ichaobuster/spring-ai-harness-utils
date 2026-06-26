package io.github.springai.harness.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.springai.harness.agent.HarnessAgents;
import io.github.springai.harness.config.AgentConfig;
import io.github.springai.harness.workspace.AgentWorkspace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * QwenPawApiController
 *
 * @author ichaobuster
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class QwenPawApiController {


    private final AgentWorkspace agentWorkspace;

    private final HarnessAgents harnessAgents;

    public QwenPawApiController(@Autowired AgentWorkspace agentWorkspace, @Autowired HarnessAgents harnessAgents) {
        this.agentWorkspace = agentWorkspace;
        this.harnessAgents = harnessAgents;
    }

    @PostMapping(value = "/console/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentStreamingResponse> chat(@RequestBody AgentRequest request) {
        // TODO userId 的其他获取方法
        AgentConfig agentConfig = agentWorkspace.loadAgentConfig(request.userId);

        Object rawContent = null;
        for (int i = request.input().size() - 1; i >= 0; i--) {
            OpenAiApi.ChatCompletionMessage message = request.input().get(i);
            if (message.role() == OpenAiApi.ChatCompletionMessage.Role.USER) {
                rawContent = message.rawContent();
                break;
            }
        }

        String userText = null;
        List<Media> media = List.of();
        if (rawContent instanceof String textContent) {
            userText = textContent;
        } else if (rawContent instanceof List) {
            List<Map<String, Object>> mediaContents = (List<Map<String, Object>>) rawContent;
            userText = mediaContents.stream()
                    .filter(c -> "text".equals(c.get("type")))
                    .map(c -> (String) c.getOrDefault("text", ""))
                    .collect(Collectors.joining());
            // TODO 处理 media
        }

        AtomicLong atomicSeqNum = new AtomicLong(0);
        return harnessAgents
                .chat(agentConfig, request.sessionId(), userText, media, null)
                .map(ccr -> {
                    List<OpenAiApi.ChatCompletionMessage.MediaContent> content = null;
                    Usage usage = null;
                    if (ccr.chatResponse() != null) {
                        if (ccr.chatResponse().getResult() != null) {
                            AssistantMessage am = ccr.chatResponse().getResult().getOutput();
                            content = List.of(new OpenAiApi.ChatCompletionMessage.MediaContent(am.getText()));
                        }
                        if (ccr.chatResponse().getMetadata() != null) {
                            usage = ccr.chatResponse().getMetadata().getUsage();
                        }
                    }

                    // TODO 其他字段细节
                    String status = "in_progress";
                    String error = null;

                    return new AgentStreamingResponse(atomicSeqNum.getAndIncrement(), "response", status, content, error, request.sessionId(), usage);
                }); // TODO 错误处理
    }

    public record AgentRequest(List<OpenAiApi.ChatCompletionMessage> input,
                               @JsonProperty("session_id") String sessionId,
                               @JsonProperty("user_id") String userId,
                               @JsonProperty(value = "channel_id", defaultValue = "console") String channelId) {
    }

    public record AgentStreamingResponse(@JsonProperty("sequence_number") long sequenceNumber,
                                         @JsonProperty(defaultValue = "response") String object,
                                         String status,
                                         List<OpenAiApi.ChatCompletionMessage.MediaContent> content,
                                         String error,
                                         @JsonProperty("session_id") String sessionId,
                                         Usage usage) {
    }
}
