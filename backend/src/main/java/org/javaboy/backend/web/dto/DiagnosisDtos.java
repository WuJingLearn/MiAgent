package org.javaboy.backend.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class DiagnosisDtos {

    private DiagnosisDtos() {
    }

    public record SunfireAlertRequest(
            @NotBlank @Size(max = 255) String alertKey,
            @NotBlank @Size(max = 500) String title,
            @NotBlank @Size(max = 50000) String content,
            @Size(max = 120) String tenantId) {
    }

    public record TaskAcceptedResponse(String taskId, String status) {
    }

    public record DiagnosisTaskResponse(
            String taskId,
            String alertEventId,
            String modelKey,
            String status,
            String report,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt) {
    }
}
