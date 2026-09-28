package org.javaboy.backend.web;

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import org.javaboy.backend.web.dto.ChatDtos.SsePayload;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Optional;

/**
 * 将 AgentScope 的流式事件映射为前端可消费的 SSE 负载。覆盖一次回复中的四类内容：
 * 模型思考（thinking）、正文回复（text）、工具调用信息（tool + 入参）、工具执行结果（tool_result）。
 * 事件生命周期遵循 start → delta → end，前端按 blockId / toolCallId 聚合还原。
 */
@Component
public class AgentEventSseMapper {

    public Optional<SseEmitter.SseEventBuilder> map(AgentEvent event) {
        SsePayload payload;

        if (event instanceof ThinkingBlockDeltaEvent delta) {
            payload = new SsePayload("thinking_delta", event.getId(), Map.of(
                    "blockId", nullToEmpty(delta.getBlockId()),
                    "delta", nullToEmpty(delta.getDelta())));
        } else if (event instanceof TextBlockDeltaEvent delta) {
            payload = new SsePayload("text_delta", event.getId(), Map.of("delta", nullToEmpty(delta.getDelta())));
        } else if (event instanceof ToolCallStartEvent toolCall) {
            payload = new SsePayload("tool_start", event.getId(), Map.of(
                    "toolCallId", nullToEmpty(toolCall.getToolCallId()),
                    "toolName", nullToEmpty(toolCall.getToolCallName())));
        } else if (event instanceof ToolCallDeltaEvent toolCall) {
            payload = new SsePayload("tool_delta", event.getId(), Map.of(
                    "toolCallId", nullToEmpty(toolCall.getToolCallId()),
                    "delta", nullToEmpty(toolCall.getDelta())));
        } else if (event instanceof ToolCallEndEvent toolCall) {
            payload = new SsePayload("tool_end", event.getId(), Map.of(
                    "toolCallId", nullToEmpty(toolCall.getToolCallId()),
                    "toolName", nullToEmpty(toolCall.getToolCallName())));
        } else if (event instanceof ToolResultStartEvent result) {
            payload = new SsePayload("tool_result_start", event.getId(), Map.of(
                    "toolCallId", nullToEmpty(result.getToolCallId()),
                    "toolName", nullToEmpty(result.getToolCallName())));
        } else if (event instanceof ToolResultTextDeltaEvent result) {
            payload = new SsePayload("tool_result_delta", event.getId(), Map.of(
                    "toolCallId", nullToEmpty(result.getToolCallId()),
                    "delta", nullToEmpty(result.getDelta())));
        } else if (event instanceof ToolResultEndEvent result) {
            ToolResultState state = result.getState();
            payload = new SsePayload("tool_result_end", event.getId(), Map.of(
                    "toolCallId", nullToEmpty(result.getToolCallId()),
                    "state", state == null ? "SUCCESS" : state.name()));
        } else if (event instanceof AgentResultEvent result) {
            String text = result.getResult() == null ? "" : result.getResult().getTextContent();
            payload = new SsePayload("result", event.getId(), Map.of("text", text == null ? "" : text));
        } else if (event instanceof AgentEndEvent) {
            payload = new SsePayload("done", event.getId(), Map.of());
        } else if (event instanceof ExceedMaxItersEvent exceeded) {
            payload = new SsePayload("error", event.getId(), Map.of(
                    "code", "MAX_ITERATIONS_EXCEEDED",
                    "maxIters", exceeded.getMaxIters()));
        } else {
            return Optional.empty();
        }
        return Optional.of(toSse(payload));
    }

    public SseEmitter.SseEventBuilder error(Throwable error) {
        String message = error.getMessage() == null ? "诊断请求失败" : error.getMessage();
        return toSse(new SsePayload("error", null, Map.of("message", message)));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private SseEmitter.SseEventBuilder toSse(SsePayload payload) {
        SseEmitter.SseEventBuilder builder = SseEmitter.event()
                .name(payload.type())
                .data(payload, MediaType.APPLICATION_JSON);
        if (payload.id() != null && !payload.id().isBlank()) {
            builder = builder.id(payload.id());
        }
        return builder;
    }
}
