package org.javaboy.backend.service;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.javaboy.backend.agent.ModelCatalog;
import org.javaboy.backend.agent.ModelRoutingMiddleware;
import org.javaboy.backend.config.SreAgentProperties;
import org.javaboy.backend.domain.DiagnosisTask;
import org.javaboy.backend.repository.DiagnosisRepository;
import org.javaboy.backend.security.UserIdentityResolver;
import org.javaboy.backend.web.dto.DiagnosisDtos.SunfireAlertRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Executor;

@Service
public class DiagnosisService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisService.class);

    private final HarnessAgent agent;
    private final DiagnosisRepository repository;
    private final UserIdentityResolver userIdentityResolver;
    private final SreAgentProperties properties;
    private final Executor executor;

    public DiagnosisService(
            HarnessAgent agent,
            DiagnosisRepository repository,
            UserIdentityResolver userIdentityResolver,
            SreAgentProperties properties,
            ModelCatalog modelCatalog,
            @Qualifier("diagnosisExecutor") Executor executor) {
        this.agent = agent;
        this.repository = repository;
        this.userIdentityResolver = userIdentityResolver;
        this.properties = properties;
        this.executor = executor;
        modelCatalog.requireModel(properties.getSunfireModel());
    }

    public String accept(SunfireAlertRequest request) {
        String userId = userIdentityResolver.serviceUser(request.tenantId());
        String sessionId = "alert-" + sha256(request.alertKey());
        String taskId = UUID.randomUUID().toString();
        String alertEventId = UUID.randomUUID().toString();
        String effectiveTaskId = repository.createTaskIfAbsent(
                alertEventId,
                request.alertKey(),
                request.title(),
                request.content(),
                taskId,
                userId,
                sessionId,
                properties.getSunfireModel());
        if (taskId.equals(effectiveTaskId)) {
            executor.execute(() -> runDiagnosis(taskId, userId, sessionId, request));
        }
        return effectiveTaskId;
    }

    public DiagnosisTask requireTask(String taskId) {
        return repository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "诊断任务不存在"));
    }

    private void runDiagnosis(String taskId, String userId, String sessionId, SunfireAlertRequest request) {
        if (!repository.markRunning(taskId)) {
            return;
        }
        RuntimeContext context = RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .put(ModelRoutingMiddleware.MODEL_KEY_CONTEXT_KEY, properties.getSunfireModel())
                .build();
        String prompt = """
                请诊断以下 Sunfire 告警。当前尚未接入外部诊断工具，只能依据告警内容分析；
                无法确认的事实必须明确标记为待验证，不得编造日志或指标。

                告警标题：%s
                告警内容：%s
                """.formatted(request.title(), request.content());

        agent.call(new UserMessage(prompt), context)
                .flatMap(reply -> Mono.fromRunnable(
                                () -> repository.markDone(taskId, reply.getTextContent()))
                        .subscribeOn(Schedulers.boundedElastic()))
                .onErrorResume(error -> Mono.fromRunnable(() -> {
                            log.error("Sunfire 告警诊断失败, taskId={}", taskId, error);
                            repository.markFailed(taskId, error.getMessage());
                        })
                        .subscribeOn(Schedulers.boundedElastic()))
                .subscribe();
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 不支持 SHA-256", impossible);
        }
    }
}
