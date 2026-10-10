package org.javaboy.backend.web;

import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.ToolUseBlock;
import org.javaboy.backend.web.dto.ChatDtos.SsePayload;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentEventSseMapperTests {

    private final AgentEventSseMapper mapper = new AgentEventSseMapper();

    @Test
    void mapsRoundAndContentEventsWithRoundId() {
        SsePayload roundStart = mapper.mapToPayload(new ModelCallStartEvent("reply-1"), "round-1").orElseThrow();
        SsePayload thinking = mapper.mapToPayload(
                new ThinkingBlockDeltaEvent("reply-1", "thinking-1", "分析中"), "round-1").orElseThrow();
        SsePayload tool = mapper.mapToPayload(
                new ToolCallStartEvent("reply-1", "tool-1", "query_logs"), "round-1").orElseThrow();

        assertEquals("round_start", roundStart.type());
        assertEquals("thinking_delta", thinking.type());
        assertEquals("tool_start", tool.type());
        assertEquals("round-1", ((Map<?, ?>) thinking.data()).get("roundId"));
        assertEquals("分析中", ((Map<?, ?>) thinking.data()).get("delta"));
        assertEquals("tool-1", ((Map<?, ?>) tool.data()).get("toolCallId"));
    }

    @Test
    void mapsUserConfirmationRequestWithToolDetails() {
        ToolUseBlock tool = new ToolUseBlock("tool-1", "query_metrics", Map.of("app", "upper"));
        SsePayload payload = mapper.mapToPayload(
                new RequireUserConfirmEvent("reply-1", java.util.List.of(tool)), "round-1").orElseThrow();

        assertEquals("confirm_required", payload.type());
        Map<?, ?> data = (Map<?, ?>) payload.data();
        assertEquals("reply-1", data.get("replyId"));
        Map<?, ?> serializedTool = (Map<?, ?>) ((java.util.List<?>) data.get("tools")).getFirst();
        assertEquals("tool-1", serializedTool.get("toolCallId"));
        assertEquals("query_metrics", serializedTool.get("toolName"));
    }
}
