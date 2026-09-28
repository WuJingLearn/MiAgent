package org.javaboy.backend.web;

import jakarta.validation.Valid;
import org.javaboy.backend.domain.DiagnosisTask;
import org.javaboy.backend.security.WebhookAuthenticator;
import org.javaboy.backend.service.DiagnosisService;
import org.javaboy.backend.web.dto.DiagnosisDtos.DiagnosisTaskResponse;
import org.javaboy.backend.web.dto.DiagnosisDtos.SunfireAlertRequest;
import org.javaboy.backend.web.dto.DiagnosisDtos.TaskAcceptedResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class SunfireWebhookController {

    private final DiagnosisService diagnosisService;
    private final WebhookAuthenticator webhookAuthenticator;

    public SunfireWebhookController(
            DiagnosisService diagnosisService,
            WebhookAuthenticator webhookAuthenticator) {
        this.diagnosisService = diagnosisService;
        this.webhookAuthenticator = webhookAuthenticator;
    }

    @PostMapping("/webhooks/sunfire")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskAcceptedResponse accept(
            @RequestHeader(value = WebhookAuthenticator.TOKEN_HEADER, required = false) String token,
            @Valid @RequestBody SunfireAlertRequest request) {
        webhookAuthenticator.verify(token);
        String taskId = diagnosisService.accept(request);
        DiagnosisTask task = diagnosisService.requireTask(taskId);
        return new TaskAcceptedResponse(taskId, task.status());
    }

    @GetMapping("/diagnosis/tasks/{taskId}")
    public DiagnosisTaskResponse getTask(
            @RequestHeader(value = WebhookAuthenticator.TOKEN_HEADER, required = false) String token,
            @PathVariable String taskId) {
        webhookAuthenticator.verify(token);
        DiagnosisTask task = diagnosisService.requireTask(taskId);
        return new DiagnosisTaskResponse(
                task.taskId(),
                task.alertEventId(),
                task.modelKey(),
                task.status(),
                task.report(),
                task.errorMessage(),
                task.createdAt(),
                task.updatedAt());
    }
}
