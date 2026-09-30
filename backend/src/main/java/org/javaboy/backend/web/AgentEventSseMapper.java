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
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
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
 *
 * <p>映射分两步：{@link #mapToPayload} 产出可序列化的 {@link SsePayload}（既能落 Redis 缓冲，也能
 * 直接下发），{@link #toSse} 再把负载组装成 {@link SseEmitter} 事件。实时推送与断线回放共用同一套负载，
 * 保证两条路径展示结构一致。
 */
@Component
public class AgentEventSseMapper {

    /**
     * 把一个 Agent 事件映射为可序列化负载。返回 {@code Optional.empty()} 表示该事件无需下发。
     */
    public Optional<SsePayload> mapToPayload(AgentEvent event) {
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
            // 用户主动中断时，框架返回的是一条 GenerateReason.INTERRUPTED 的“恢复提示”消息，
            // 并非真实回复。此处跳过，避免用它覆盖前端已累积的半截正文；已生成内容此前已通过
            // text_delta 下发并落库（state_data），历史回显仍能看到。
            Msg resultMsg = result.getResult();
            if (resultMsg != null && resultMsg.getGenerateReason() == GenerateReason.INTERRUPTED) {
                return Optional.empty();
            }
            String text = resultMsg == null ? "" : resultMsg.getTextContent();
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
        return Optional.of(payload);
    }

    /** 生成一条 error 负载，用于把后台异常以 SSE 形式下发给前端。 */
    public SsePayload errorPayload(Throwable error) {
        String message = error.getMessage() == null ? "诊断请求失败" : error.getMessage();
        return new SsePayload("error", null, Map.of("message", message));
    }

    /** 把负载组装成 SSE 事件；实时推送与缓冲回放共用。 */
    public SseEmitter.SseEventBuilder toSse(SsePayload payload) {
        SseEmitter.SseEventBuilder builder = SseEmitter.event()
                .name(payload.type())
                .data(payload, MediaType.APPLICATION_JSON);
        if (payload.id() != null && !payload.id().isBlank()) {
            builder = builder.id(payload.id());
        }
        return builder;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
