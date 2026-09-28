package org.javaboy.backend.web;

import io.agentscope.core.agent.RuntimeContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.javaboy.backend.domain.ChatSession;
import org.javaboy.backend.security.UserIdentityResolver;
import org.javaboy.backend.service.ChatService;
import org.javaboy.backend.service.ChatSessionService;
import org.javaboy.backend.service.SessionHistoryService;
import org.javaboy.backend.web.dto.ChatDtos.ChatMessageRequest;
import org.javaboy.backend.web.dto.ChatDtos.CreateSessionRequest;
import org.javaboy.backend.web.dto.ChatDtos.HistoryMessage;
import org.javaboy.backend.web.dto.ChatDtos.SessionResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/chat/sessions")
public class ChatController {

    private final ChatSessionService sessionService;
    private final ChatService chatService;
    private final SessionHistoryService historyService;
    private final UserIdentityResolver userIdentityResolver;
    private final AgentEventSseMapper eventMapper;
    private final Executor chatStreamExecutor;

    public ChatController(
            ChatSessionService sessionService,
            ChatService chatService,
            SessionHistoryService historyService,
            UserIdentityResolver userIdentityResolver,
            AgentEventSseMapper eventMapper,
            @Qualifier("chatStreamExecutor") Executor chatStreamExecutor) {
        this.sessionService = sessionService;
        this.chatService = chatService;
        this.historyService = historyService;
        this.userIdentityResolver = userIdentityResolver;
        this.eventMapper = eventMapper;
        this.chatStreamExecutor = chatStreamExecutor;
    }

    @PostMapping
    public SessionResponse createSession(
            HttpServletRequest servletRequest,
            @Valid @RequestBody CreateSessionRequest request) {
        String userId = userIdentityResolver.resolve(servletRequest);
        return toResponse(sessionService.create(userId, request.modelKey(), request.title()));
    }

    @GetMapping
    public List<SessionResponse> listSessions(HttpServletRequest request) {
        String userId = userIdentityResolver.resolve(request);
        return sessionService.list(userId).stream().map(this::toResponse).toList();
    }

    @GetMapping("/{sessionId}/messages")
    public List<HistoryMessage> listMessages(HttpServletRequest request, @PathVariable String sessionId) {
        String userId = userIdentityResolver.resolve(request);
        sessionService.requireOwned(sessionId, userId);
        return historyService.loadMessages(userId, sessionId);
    }

    @PostMapping(value = "/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(
            HttpServletRequest servletRequest,
            @PathVariable String sessionId,
            @Valid @RequestBody ChatMessageRequest request) {
        String userId = userIdentityResolver.resolve(servletRequest);
        // 同步校验：归属(404)/会话关闭(409)/重复请求(409) 在此直接以 HTTP 状态码返回
        RuntimeContext context = chatService.prepare(userId, sessionId, request.requestId());

        // 0 表示不设超时，由「流正常结束」或「客户端断开」来终止这条 SSE
        SseEmitter emitter = new SseEmitter(0L);
        // 客户端断开 / 超时 / 完成都会触发这里，用于让后台迭代尽快停下
        AtomicBoolean disconnected = new AtomicBoolean(false);
        emitter.onError(error -> disconnected.set(true));
        emitter.onTimeout(() -> disconnected.set(true));
        emitter.onCompletion(() -> disconnected.set(true));

        chatStreamExecutor.execute(() -> {
            try {
                chatService.streamToConsumer(userId, sessionId, request.requestId(), request.message(), context, event -> {
                    if (disconnected.get()) {
                        // 客户端已断开，抛出以中断迭代并取消上游 Agent
                        throw new UncheckedIOException(new IOException("客户端已断开连接"));
                    }
                    eventMapper.map(event).ifPresent(sse -> send(emitter, sse));
                });
                emitter.complete();
            } catch (Throwable error) {
                if (disconnected.get()) {
                    emitter.complete();
                } else {
                    sendError(emitter, error);
                }
            }
        });
        return emitter;
    }

    private void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException error) {
            // 通常是客户端断开导致的写失败，向上抛出以停止迭代
            throw new UncheckedIOException(error);
        }
    }

    private void sendError(SseEmitter emitter, Throwable error) {
        try {
            emitter.send(eventMapper.error(error));
            emitter.complete();
        } catch (IOException ignored) {
            emitter.completeWithError(error);
        }
    }

    private SessionResponse toResponse(ChatSession session) {
        return new SessionResponse(
                session.sessionId(),
                session.modelKey(),
                session.title(),
                session.status(),
                session.createdAt(),
                session.updatedAt());
    }
}
