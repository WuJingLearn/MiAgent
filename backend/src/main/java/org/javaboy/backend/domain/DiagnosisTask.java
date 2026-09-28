package org.javaboy.backend.domain;

import java.time.Instant;

public record DiagnosisTask(
        String taskId,
        String alertEventId,
        String userId,
        String sessionId,
        String modelKey,
        String status,
        String report,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {
}
