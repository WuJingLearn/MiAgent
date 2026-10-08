package org.javaboy.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.javaboy.backend.config.SreAgentProperties;
import org.javaboy.backend.repository.ChatSessionRepository;
import org.javaboy.backend.service.SessionLeaseService.Lease;
import org.javaboy.backend.service.StreamEventStore.TurnStatus;
import org.javaboy.backend.web.AgentEventSseMapper;
import org.javaboy.backend.web.dto.ChatDtos.SsePayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * 流式对话服务：把「Agent 生成」与「HTTP 连接」彻底解耦。
 *
 * <p>一轮对话由 {@link #startTurn} 在后台线程启动，Agent 事件被映射成负载后写入 {@link StreamEventStore}
 * 的 Redis 缓冲（“可回放磁带”），生成生命周期不依赖任何 HTTP 连接。{@link #attach} 则为某个客户端建立
 * 一条 tail：从缓冲回放已生成事件并轮询续读，直到本轮结束。因此：
 * <ul>
 *   <li>刷新页面 → 旧 tail 断开，但后台生成继续；重连后可从缓冲回放并续看实时输出；</li>
 *   <li>点击停止 → 调 {@link #interrupt}，AgentScope 框架优雅中断，聚合已生成内容落库并结束本轮。</li>
 * </ul>
 */
@Service
public class ChatStreamService {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamService.class);

    private final HarnessAgent agent;
    private final ChatSessionRepository sessionRepository;
    private final SessionLeaseService leaseService;
    private final SreAgentProperties properties;
    private final StreamEventStore store;
    private final AgentEventSseMapper mapper;
    // 本应用未注册 ObjectMapper bean（与 SessionHistoryService 一致），此处自建一个用于负载序列化。
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Executor chatStreamExecutor;
    /** 每轮一个心跳任务，跑在这个共享调度器上，避免为每轮单独建线程。 */
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newScheduledThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "stream-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    public ChatStreamService(
            HarnessAgent agent,
            ChatSessionRepository sessionRepository,
            SessionLeaseService leaseService,
            SreAgentProperties properties,
            StreamEventStore store,
            AgentEventSseMapper mapper,
            @Qualifier("chatStreamExecutor") Executor chatStreamExecutor) {
        this.agent = agent;
        this.sessionRepository = sessionRepository;
        this.leaseService = leaseService;
        this.properties = properties;
        this.store = store;
        this.mapper = mapper;
        this.chatStreamExecutor = chatStreamExecutor;
    }

    /**
     * 启动一轮后台生成并立即返回 turnId。turnId 复用 requestId，与 chat_turn 行一一对应。
     * 调用方随后用 {@link #attach} 建立 tail 观看输出。
     */
    public String startTurn(String userId, String sessionId, String requestId, String message, RuntimeContext context) {
        store.register(sessionId, requestId);
        chatStreamExecutor.execute(() -> runGeneration(userId, sessionId, requestId, message, context));
        return requestId;
    }

    private void runGeneration(String userId, String sessionId, String requestId, String message, RuntimeContext context) {
        // 5s
        int heartbeatSeconds = properties.getStream().getHeartbeatSeconds();
        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        store.heartbeat(sessionId, requestId);
                    } catch (Exception ex) {
                        log.warn("流式心跳刷新失败 sessionId={} requestId={}", sessionId, requestId, ex);
                    }
                },
                heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);

        Lease lease = null;
        StringBuilder streamed = new StringBuilder();
        String resultText = null;
        boolean interrupted = false;
        boolean finalized = false;
        ReActRoundTracker rounds = new ReActRoundTracker();
        try {
            lease = leaseService.acquire(userId, sessionId, properties.getSessionLockSeconds()).block();
            try (Stream<AgentEvent> events = agent.streamEvents(new UserMessage(message), context).toStream()) {
                var iterator = events.iterator();
                while (iterator.hasNext()) {
                    AgentEvent event = iterator.next();
                    if (event instanceof TextBlockDeltaEvent delta && delta.getDelta() != null) {
                        streamed.append(delta.getDelta());
                    } else if (event instanceof AgentResultEvent result && result.getResult() != null) {
                        Msg resultMsg = result.getResult();
                        if (resultMsg.getGenerateReason() == GenerateReason.INTERRUPTED) {
                            interrupted = true;
                        } else if (resultMsg.getTextContent() != null) {
                            resultText = resultMsg.getTextContent();
                        }
                    }
                    mapper.mapToPayload(event, rounds.roundIdFor(event))
                            .ifPresent(payload -> appendPayload(sessionId, requestId, payload));

                    // 方案A：AgentResultEvent(agentEnd) 是语义上的「回答结束」点。收到即落库并标记 DONE，
                    // 让前端 tail 立刻收尾，不再等待 MemoryFlushMiddleware 的 concatWith 把整条流拖到 flush 完成。
                    // 长期记忆的 flush/offload 仍随流在后台自然跑完；lease 到循环结束才释放，避免同会话并发 flush 竞争。
                    if (event instanceof AgentResultEvent && !finalized) {
                        finalizeTurn(sessionId, requestId, resultText, streamed, interrupted);
                        finalized = true;
                    }
                }
            }
            if (!finalized) {
                // 兜底：整条流结束都没出现 AgentResultEvent（异常情况），按原逻辑收尾一次。
                finalizeTurn(sessionId, requestId, resultText, streamed, interrupted);
                finalized = true;
            }
        } catch (Throwable error) {
            log.error("流式生成异常 sessionId={} requestId={}", sessionId, requestId, error);
            // 已收尾（内容已全量输出且落库、状态已 DONE）后发生的异常（如后台 flush）不应把本轮翻转为失败。
            if (!finalized) {
                sessionRepository.failTurn(sessionId, requestId, error.getMessage());
                appendPayload(sessionId, requestId, mapper.errorPayload(error));
                store.markFailed(sessionId, requestId);
            }
        } finally {
            heartbeat.cancel(false);
            if (lease != null) {
                try {
                    leaseService.release(lease).block();
                } catch (RuntimeException ex) {
                    log.warn("释放会话执行锁失败: sessionId={}, requestId={}", sessionId, requestId, ex);
                }
            }
        }
    }

    /**
     * 收尾一轮生成：落库最终回复并把 turn 状态标记为 DONE。
     *
     * <p>方案A 下在收到 {@link AgentResultEvent}(agentEnd) 时即调用，使前端 tail 不必等待后台记忆 flush；
     * 若整条流未出现 {@link AgentResultEvent}，则在流结束后兜底调用一次。调用方通过 {@code finalized}
     * 标志保证本方法对同一轮至多执行一次，故 {@code completeTurn}/{@code markDone} 不会重复触发。
     */
    private void finalizeTurn(
            String sessionId, String requestId, String resultText, StringBuilder streamed, boolean interrupted) {
        String finalResponse = resultText != null ? resultText : streamed.toString();
        sessionRepository.completeTurn(sessionId, requestId, finalResponse);
        store.markDone(sessionId, requestId);
        log.info("流式生成结束 sessionId={} requestId={} interrupted={} length={}",
                sessionId, requestId, interrupted, finalResponse.length());
    }

    /** 每次生成独有的轮次上下文，禁止放进单例 Mapper，避免并发会话混用轮次。 */
    private static final class ReActRoundTracker {
        private int roundIndex;
        private String currentRoundId;

        String roundIdFor(AgentEvent event) {
            if (event instanceof ModelCallStartEvent) {
                currentRoundId = "round-" + (++roundIndex);
            }
            if (currentRoundId == null) {
                currentRoundId = "round-" + (++roundIndex);
            }
            return currentRoundId;
        }
    }

    private void appendPayload(String sessionId, String turnId, SsePayload payload) {
        try {
            store.append(sessionId, turnId, objectMapper.writeValueAsString(payload));
        } catch (Exception ex) {
            log.warn("写入流式事件缓冲失败 sessionId={} turnId={} type={}", sessionId, turnId, payload.type(), ex);
        }
    }

    /**
     * 为客户端建立一条 tail：从 offset 起回放缓冲事件并轮询续读，直到本轮结束或客户端断开。
     * 客户端断开只会结束本 tail，不影响后台生成。
     */
    public void attach(SseEmitter emitter, String sessionId, String turnId, long offset, AtomicBoolean disconnected) {
        chatStreamExecutor.execute(() -> tail(emitter, sessionId, turnId, offset, disconnected));
    }

    private void tail(SseEmitter emitter, String sessionId, String turnId, long offset, AtomicBoolean disconnected) {
        long pollMillis = properties.getStream().getPollMillis();
        try {
            while (true) {
                if (disconnected.get()) {
                    break;
                }
                TurnStatus status = store.status(sessionId, turnId);
                List<String> events = store.range(sessionId, turnId, offset);
                for (String json : events) {
                    emitter.send(mapper.toSse(objectMapper.readValue(json, SsePayload.class)));
                    offset++;
                }
                String state = status.state();
                if (StreamEventStore.STATE_DONE.equals(state) || StreamEventStore.STATE_FAILED.equals(state)) {
                    break;
                }
                if (StreamEventStore.STATE_STALE.equals(state) || StreamEventStore.STATE_NONE.equals(state)) {
                    emitter.send(mapper.toSse(new SsePayload("error", null,
                            java.util.Map.of("message", "本轮生成已中断或丢失，请重试"))));
                    break;
                }
                Thread.sleep(pollMillis);
            }
            emitter.complete();
        } catch (IOException ex) {
            // 客户端断开：结束 tail，后台生成继续。
            disconnected.set(true);
            emitter.complete();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (Exception ex) {
            log.warn("tail 读取缓冲异常 sessionId={} turnId={}", sessionId, turnId, ex);
            emitter.complete();
        }
    }

    /** 当本轮不在运行时，向重连客户端回一条状态事件后立即结束（前端据此改为从历史加载）。 */
    public void attachStatusOnly(SseEmitter emitter, String status) {
        chatStreamExecutor.execute(() -> {
            try {
                emitter.send(mapper.toSse(new SsePayload("reconnect_status", null,
                        java.util.Map.of("status", status))));
                emitter.complete();
            } catch (Exception ex) {
                emitter.complete();
            }
        });
    }

    /** 查询某会话当前轮的状态（自动解析 currentTurn）。 */
    public TurnStatus status(String sessionId) {
        return store.status(sessionId, store.currentTurn(sessionId));
    }

    /** 查询指定轮的状态。 */
    public TurnStatus statusFor(String sessionId, String turnId) {
        return store.status(sessionId, turnId);
    }

    /** 解析某会话当前（最近一次）的 turnId。 */
    public String currentTurn(String sessionId) {
        return store.currentTurn(sessionId);
    }

    /**
     * 用户主动停止：调用 AgentScope 的 per-session interrupt，框架会聚合已生成内容落库并优雅结束本轮。
     * 必须用 {@code getDelegate().interrupt(userId, sessionId)}，无参 {@code interrupt()} 只作用于默认会话。
     */
    public void interrupt(String userId, String sessionId) {
        agent.getDelegate().interrupt(userId, sessionId);
        log.info("已发送中断信号 sessionId={}", sessionId);
    }
}
