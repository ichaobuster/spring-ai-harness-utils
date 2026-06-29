package io.github.springai.harness.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.util.json.JsonParser;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * HumanInTheLoopAfterAdvisor Used in conjunction with {@link HumanInTheLoopBeforeAdvisor} to supply the toolId after HITL is triggered.
 *
 * @author buyc
 */
@Slf4j
public class HumanInTheLoopAfterAdvisor implements BaseAdvisor {

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain advisorChain) {
        if (response.chatResponse() == null || response.chatResponse().getResult() == null
                || response.chatResponse().getResult().getMetadata() == null
                || response.chatResponse().getResult().getOutput() == null) {
            return response;
        }

        ChatResponse cr = response.chatResponse();
        AssistantMessage am = cr.getResult().getOutput();

        String toolId = cr.getResult().getMetadata().get("toolId");
        String finishReason = cr.getResult().getMetadata().getFinishReason();
        String messageText = am.getText();
        if (!StringUtils.hasText(toolId) || !"returnDirect".equals(finishReason) || !StringUtils.hasText(messageText)) {
            return response;
        }
        try {
            HumanInTheLoopSpec.HitlRequest hitlRequest = JsonParser.fromJson(messageText, HumanInTheLoopSpec.HitlRequest.class);
            if (!hitlRequest.isHitlRequired()) {
                return response;
            }

            hitlRequest.setToolId(toolId);
            AssistantMessage newMsg = AssistantMessage.builder()
                    .toolCalls(am.getToolCalls())
                    .properties(am.getMetadata())
                    .media(am.getMedia())
                    .content(JsonParser.toJson(hitlRequest))
                    .build();

            return response.mutate()
                    .chatResponse(ChatResponse.builder()
                            .metadata(cr.getMetadata())
                            .generations(List.of(new Generation(newMsg, cr.getResult().getMetadata())))
                            .build())
                    .build();
        } catch (Exception e) {
            log.debug(e.getMessage(), e);
            return response;
        }
    }

    @Override
    public int getOrder() {
        // Before the ToolCallAdvisor BaseAdvisor.HIGHEST_PRECEDENCE + 300
        return BaseAdvisor.HIGHEST_PRECEDENCE + 250;
    }
}
