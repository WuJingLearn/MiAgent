package org.javaboy.backend.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class ChatDtos {

    private ChatDtos() {
    }

    public record CreateSessionRequest(
            @NotBlank String modelKey,
            @Size(max = 200) String title) {
    }

    public record ChatMessageRequest(
            @NotBlank @Size(max = 20000) String message,
            @NotBlank @Size(max = 100) String requestId) {
    }

    /** 对当前会话中等待人工确认的工具调用作出批准或拒绝决定。 */
    public record ConfirmToolRequest(
            @NotBlank @Size(max = 100) String requestId,
            @NotBlank List<@NotBlank @Size(max = 200) String> toolCallIds,
            boolean confirmed) {
    }

    public record SessionResponse(
            String sessionId,
            String modelKey,
            String title,
            String status,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record SsePayload(String type, String id, Object data) {
    }

    public record HistoryMessage(
            String id,
            String role,
            String content,
            String reasoning,
            String createdAt,
            String status,
            List<HistoryToolCall> tools,
            List<ReActRound> rounds) {
    }

    public record ReActRound(
            String id,
            int index,
            String status,
            String content,
            String reasoning,
            List<HistoryToolCall> tools) {
    }

    public record HistoryToolCall(
            String id,
            String name,
            String status,
            String input,
            String result,
            String resultState) {
    }

    public record StreamStatusResponse(
            String state,
            String turnId,
            long startedAt) {
    }

    public record ReconnectRequest(
            String turnId,
            Long offset) {
    }
}
