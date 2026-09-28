package org.javaboy.backend.domain;

import java.time.Instant;

public record ChatSession(
        String sessionId,
        String userId,
        String modelKey,
        String title,
        String status,
        Instant createdAt,
        Instant updatedAt) {
}
