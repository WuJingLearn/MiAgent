package org.javaboy.backend.web;

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
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

import java.util.HashMap;
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
    public Optional<SsePayload> mapToPayload(AgentEvent event, String roundId) {
        SsePayload payload;
        if (event instanceof ModelCallStartEvent) {
            payload = new SsePayload("round_start", event.getId(), data(roundId));
        } else if (event instanceof ModelCallEndEvent end) {
            payload = new SsePayload("round_model_end", event.getId(), data(roundId,
                    "inputTokens", end.getUsage() == null ? null : end.getUsage().getInputTokens(),
                    "outputTokens", end.getUsage() == null ? null : end.getUsage().getOutputTokens()));
        } else if (event instanceof ThinkingBlockStartEvent start) {
            payload = new SsePayload("thinking_start", event.getId(), data(roundId, "blockId", start.getBlockId()));
        } else if (event instanceof ThinkingBlockDeltaEvent delta) {
            payload = new SsePayload("thinking_delta", event.getId(), data(roundId, "blockId", delta.getBlockId(), "delta", delta.getDelta()));
        } else if (event instanceof ThinkingBlockEndEvent end) {
            payload = new SsePayload("thinking_end", event.getId(), data(roundId, "blockId", end.getBlockId()));
        } else if (event instanceof TextBlockStartEvent start) {
            payload = new SsePayload("text_start", event.getId(), data(roundId, "blockId", start.getBlockId()));
        } else if (event instanceof TextBlockDeltaEvent delta) {
            payload = new SsePayload("text_delta", event.getId(), data(roundId, "delta", delta.getDelta()));
        } else if (event instanceof TextBlockEndEvent end) {
            payload = new SsePayload("text_end", event.getId(), data(roundId, "blockId", end.getBlockId()));
        } else if (event instanceof ToolCallStartEvent tool) {
            payload = new SsePayload("tool_start", event.getId(), data(roundId, "toolCallId", tool.getToolCallId(), "toolName", tool.getToolCallName()));
        } else if (event instanceof ToolCallDeltaEvent tool) {
            payload = new SsePayload("tool_delta", event.getId(), data(roundId, "toolCallId", tool.getToolCallId(), "delta", tool.getDelta()));
        } else if (event instanceof ToolCallEndEvent tool) {
            payload = new SsePayload("tool_end", event.getId(), data(roundId, "toolCallId", tool.getToolCallId(), "toolName", tool.getToolCallName()));
        } else if (event instanceof ToolResultStartEvent result) {
            payload = new SsePayload("tool_result_start", event.getId(), data(roundId, "toolCallId", result.getToolCallId(), "toolName", result.getToolCallName()));
        } else if (event instanceof ToolResultTextDeltaEvent result) {
            payload = new SsePayload("tool_result_delta", event.getId(), data(roundId, "toolCallId", result.getToolCallId(), "delta", result.getDelta()));
        } else if (event instanceof ToolResultEndEvent result) {
            ToolResultState state = result.getState();
            payload = new SsePayload("tool_result_end", event.getId(), data(roundId, "toolCallId", result.getToolCallId(), "state", state == null ? "SUCCESS" : state.name()));
        } else if (event instanceof RequireUserConfirmEvent confirmation) {
            payload = new SsePayload("confirm_required", event.getId(), data(roundId,
                    "replyId", confirmation.getReplyId(),
                    "tools", confirmation.getToolCalls().stream().map(tool -> Map.of(
                            "toolCallId", tool.getId(), "toolName", tool.getName(), "input", tool.getInput())).toList()));
        } else if (event instanceof AgentResultEvent result) {
            Msg resultMsg = result.getResult();
            if (resultMsg != null && (resultMsg.getGenerateReason() == GenerateReason.INTERRUPTED
                    || resultMsg.getGenerateReason() == GenerateReason.PERMISSION_ASKING)) return Optional.empty();
            payload = new SsePayload("result", event.getId(), data(roundId, "text", resultMsg == null ? "" : nullToEmpty(resultMsg.getTextContent())));
        } else if (event instanceof AgentEndEvent) {
            payload = new SsePayload("done", event.getId(), data(roundId));
        } else if (event instanceof ExceedMaxItersEvent exceeded) {
            payload = new SsePayload("error", event.getId(), data(roundId, "code", "MAX_ITERATIONS_EXCEEDED", "maxIters", exceeded.getMaxIters()));
        } else return Optional.empty();
        return Optional.of(payload);
    }

    private Map<String, Object> data(String roundId, Object... entries) {
        Map<String, Object> data = new HashMap<>();
        if (roundId != null && !roundId.isBlank()) data.put("roundId", roundId);
        for (int i = 0; i < entries.length; i += 2) {
            if (entries[i + 1] != null) data.put((String) entries[i], entries[i + 1]);
        }
        return data;
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
