package org.javaboy.backend.service;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.extern.slf4j.Slf4j;
import org.javaboy.backend.agent.ModelRoutingMiddleware;
import org.javaboy.backend.config.SreAgentProperties;
import org.javaboy.backend.domain.ChatSession;
import org.javaboy.backend.repository.ChatSessionRepository;
import org.javaboy.backend.service.SessionLeaseService.Lease;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Iterator;
import java.util.function.Consumer;
import java.util.stream.Stream;

@Slf4j
@Service
public class ChatService {

    private final HarnessAgent agent;
    private final ChatSessionService sessionService;
    private final ChatSessionRepository sessionRepository;
    private final SessionLeaseService leaseService;
    private final SreAgentProperties properties;

    public ChatService(
            HarnessAgent agent,
            ChatSessionService sessionService,
            ChatSessionRepository sessionRepository,
            SessionLeaseService leaseService,
            SreAgentProperties properties) {
        this.agent = agent;
        this.sessionService = sessionService;
        this.sessionRepository = sessionRepository;
        this.leaseService = leaseService;
        this.properties = properties;
    }

    /**
     * 在真正开始流式之前做同步校验并登记本轮对话。放在这里而不是流里，是为了让
     * 归属错误（404）、会话已关闭（409）、重复 requestId（409）能直接以 HTTP 状态码返回，
     * 而不是变成一条只能在 200 响应体里看到的 SSE error 事件。
     *
     * @return 传给 Agent 的运行时上下文（携带用户、会话与所选模型）
     */
    public RuntimeContext prepare(String userId, String sessionId, String requestId) {
        ChatSession session = sessionService.requireOwned(sessionId, userId);
        if (!"OPEN".equals(session.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "会话已关闭");
        }
        if (!sessionRepository.startTurn(sessionId, requestId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "重复的 requestId");
        }
        return RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .put(ModelRoutingMiddleware.MODEL_KEY_CONTEXT_KEY, session.modelKey())
                .build();
    }

    /**
     * 以阻塞方式逐个消费 Agent 的流式事件，并交给 {@code onEvent} 处理（通常是写入 SSE）。
     * 该方法应在后台线程执行：先抢占会话锁，再驱动 Agent，正常结束时登记轮次完成，
     * 出错或客户端断开（{@code onEvent} 抛异常）时登记失败并向上抛出；无论何种结局都会释放会话锁。
     *
     * <p>{@code streamEvents} 返回的是 Reactor 的 {@code Flux}，这里用 {@code toStream()}
     * 把它桥接成普通的阻塞迭代，避免业务代码里出现反应式算子；try-with-resources 关闭 Stream
     * 时会取消上游订阅，从而在客户端断开时让 Agent 停止继续推进。
     */
    public void streamToConsumer(
            String userId,
            String sessionId,
            String requestId,
            String message,
            RuntimeContext context,
            Consumer<AgentEvent> onEvent) {
        Lease lease = null;
        String finalResponse = "";
        try {
            lease = leaseService.acquire(userId, sessionId, properties.getSessionLockSeconds()).block();
            try (Stream<AgentEvent> events = agent.streamEvents(new UserMessage(message), context).toStream()) {
                Iterator<AgentEvent> iterator = events.iterator();
                while (iterator.hasNext()) {
                    AgentEvent event = iterator.next();
                    if (event instanceof AgentResultEvent result && result.getResult() != null) {
                        String text = result.getResult().getTextContent();
                        if (text != null) {
                            finalResponse = text;
                        }
                    }
                    onEvent.accept(event);
                }
            }
            sessionRepository.completeTurn(sessionId, requestId, finalResponse);
        } catch (Throwable error) {
            log.error("Error streaming events to consumer", error);
            sessionRepository.failTurn(sessionId, requestId, error.getMessage());
            throw error;
        } finally {
            if (lease != null) {
                leaseService.release(lease).block();
            }
        }
    }
}
