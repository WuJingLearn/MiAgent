package org.javaboy.backend.web;

import io.agentscope.core.agent.RuntimeContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.javaboy.backend.domain.ChatSession;
import org.javaboy.backend.security.UserIdentityResolver;
import org.javaboy.backend.service.ChatService;
import org.javaboy.backend.service.ChatSessionService;
import org.javaboy.backend.service.ChatStreamService;
import org.javaboy.backend.service.SessionHistoryService;
import org.javaboy.backend.service.StreamEventStore;
import org.javaboy.backend.web.dto.ChatDtos.ChatMessageRequest;
import org.javaboy.backend.web.dto.ChatDtos.ConfirmToolRequest;
import org.javaboy.backend.web.dto.ChatDtos.CreateSessionRequest;
import org.javaboy.backend.web.dto.ChatDtos.HistoryMessage;
import org.javaboy.backend.web.dto.ChatDtos.ReconnectRequest;
import org.javaboy.backend.web.dto.ChatDtos.SessionResponse;
import org.javaboy.backend.web.dto.ChatDtos.StreamStatusResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RestController
@RequestMapping("/api/chat/sessions")
public class ChatController {

    private final ChatSessionService sessionService;
    private final ChatService chatService;
    private final ChatStreamService chatStreamService;
    private final SessionHistoryService historyService;
    private final UserIdentityResolver userIdentityResolver;

    public ChatController(
            ChatSessionService sessionService,
            ChatService chatService,
            ChatStreamService chatStreamService,
            SessionHistoryService historyService,
            UserIdentityResolver userIdentityResolver) {
        this.sessionService = sessionService;
        this.chatService = chatService;
        this.chatStreamService = chatStreamService;
        this.historyService = historyService;
        this.userIdentityResolver = userIdentityResolver;
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

    /**
     * 发送消息：同步校验后启动一轮「后台生成」（生命周期与连接解耦），再为本次请求建立 tail 观看输出。
     * 客户端断开只结束 tail，后台生成继续，刷新后可通过 reconnect 续流。
     */
    @PostMapping(value = "/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(
            HttpServletRequest servletRequest,
            @PathVariable String sessionId,
            @Valid @RequestBody ChatMessageRequest request) {
        String userId = userIdentityResolver.resolve(servletRequest);
        // 同步校验：归属(404)/会话关闭(409)/重复请求(409) 在此直接以 HTTP 状态码返回
        RuntimeContext context = chatService.prepare(userId, sessionId, request.requestId());

        // 启动后台生成（写入 Redis 事件缓冲），返回 turnId（复用 requestId）
        String turnId = chatStreamService.startTurn(userId, sessionId, request.requestId(), request.message(), context);

        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean disconnected = new AtomicBoolean(false);
        emitter.onError(error -> disconnected.set(true));
        emitter.onTimeout(() -> disconnected.set(true));
        emitter.onCompletion(() -> disconnected.set(true));
        chatStreamService.attach(emitter, sessionId, turnId, 0L, disconnected);
        return emitter;
    }

    /**
     * 对等待人工确认的工具调用作出批准或拒绝决定，并以新的 SSE 流继续同一个 Agent 会话。
     */
    @PostMapping(value = "/{sessionId}/confirm", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter confirmToolCall(
            HttpServletRequest servletRequest,
            @PathVariable String sessionId,
            @Valid @RequestBody ConfirmToolRequest request) {
        String userId = userIdentityResolver.resolve(servletRequest);
        sessionService.requireOwned(sessionId, userId);
        chatStreamService.validatePendingConfirmations(userId, sessionId, request.toolCallIds());
        RuntimeContext context = chatService.prepareConfirmation(userId, sessionId, request.requestId());
        String turnId = chatStreamService.resumeAfterConfirmation(
                userId, sessionId, request.requestId(), context, request.toolCallIds(), request.confirmed());

        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean disconnected = new AtomicBoolean(false);
        emitter.onError(error -> disconnected.set(true));
        emitter.onTimeout(() -> disconnected.set(true));
        emitter.onCompletion(() -> disconnected.set(true));
        chatStreamService.attach(emitter, sessionId, turnId, 0L, disconnected);
        return emitter;
    }

    /** 查询某会话当前轮的流状态，供前端刷新后判断是否需要重连续流。 */
    @GetMapping("/{sessionId}/stream/status")
    public StreamStatusResponse streamStatus(HttpServletRequest servletRequest, @PathVariable String sessionId) {
        String userId = userIdentityResolver.resolve(servletRequest);
        sessionService.requireOwned(sessionId, userId);
        StreamEventStore.TurnStatus status = chatStreamService.status(sessionId);
        return new StreamStatusResponse(status.state(), status.turnId(), status.startedAt());
    }

    /**
     * 重连续流：若本轮仍在运行，从缓冲 offset 起回放并继续 tail；否则回一条 reconnect_status 让前端改从历史加载。
     * 无论哪种情况都以 SSE 返回，简化前端处理。
     */
    @PostMapping(value = "/{sessionId}/stream/reconnect", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter reconnect(
            HttpServletRequest servletRequest,
            @PathVariable String sessionId,
            @RequestBody(required = false) ReconnectRequest request) {
        String userId = userIdentityResolver.resolve(servletRequest);
        sessionService.requireOwned(sessionId, userId);

        String turnId = request != null && request.turnId() != null && !request.turnId().isBlank()
                ? request.turnId()
                : chatStreamService.currentTurn(sessionId);
        long offset = request != null && request.offset() != null ? request.offset() : 0L;

        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean disconnected = new AtomicBoolean(false);
        emitter.onError(error -> disconnected.set(true));
        emitter.onTimeout(() -> disconnected.set(true));
        emitter.onCompletion(() -> disconnected.set(true));

        StreamEventStore.TurnStatus status = chatStreamService.statusFor(sessionId, turnId);
        if (status.running()) {
            chatStreamService.attach(emitter, sessionId, turnId, offset, disconnected);
        } else {
            // 非运行态：告知前端本轮已结束/丢失，改从历史接口加载已持久化内容
            chatStreamService.attachStatusOnly(emitter, status.state());
        }
        return emitter;
    }

    /** 用户主动停止：调用 AgentScope per-session interrupt，优雅结束并持久化已生成内容。 */
    @PostMapping("/{sessionId}/stream/interrupt")
    public Map<String, Object> interrupt(HttpServletRequest servletRequest, @PathVariable String sessionId) {
        String userId = userIdentityResolver.resolve(servletRequest);
        sessionService.requireOwned(sessionId, userId);
        chatStreamService.interrupt(userId, sessionId);
        return Map.of("ok", true);
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
