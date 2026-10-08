package org.javaboy.backend.service;

import org.javaboy.backend.config.SreAgentProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 流式事件的 Redis 缓冲（“可回放磁带”）。每一轮对话生成一个 turnId，Agent 产生的事件被序列化成
 * JSON 追加到 Redis List；HTTP 侧（首发或重连）从指定 offset 回放并轮询续读，直到本轮结束。
 *
 * <p>这套机制把「Agent 生成」与「HTTP 连接」解耦：客户端断开只影响 tail 读取，后台生成不受影响，
 * 因此刷新页面后仍可通过 status/reconnect 拿回已生成的内容并继续观看实时输出。
 *
 * <p>Key 布局（前缀来自 {@code sreagent.stream.key-prefix}）：
 * <ul>
 *   <li>{@code events:{sessionId}:{turnId}} —— List，逐条事件 JSON</li>
 *   <li>{@code meta:{sessionId}:{turnId}}   —— Hash，state / startedAt / hb</li>
 *   <li>{@code cur:{sessionId}}             —— String，当前 turnId</li>
 * </ul>
 */
@Component
public class StreamEventStore {

    public static final String STATE_RUNNING = "running";
    public static final String STATE_DONE = "done";
    public static final String STATE_FAILED = "failed";
    /** 派生态：running 但心跳超时，通常意味着持有该轮的实例已宕机。 */
    public static final String STATE_STALE = "stale";
    /** 派生态：没有任何缓冲记录。 */
    public static final String STATE_NONE = "none";

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final Duration ttl;
    private final int staleAfterSeconds;

    public StreamEventStore(StringRedisTemplate redis, SreAgentProperties properties) {
        this.redis = redis;
        SreAgentProperties.Stream cfg = properties.getStream();
        this.keyPrefix = cfg.getKeyPrefix();
        this.ttl = Duration.ofMinutes(cfg.getTtlMinutes());
        this.staleAfterSeconds = cfg.getStaleAfterSeconds();
    }

    private String eventsKey(String sessionId, String turnId) {
        return keyPrefix + "events:" + sessionId + ":" + turnId;
    }

    private String metaKey(String sessionId, String turnId) {
        return keyPrefix + "meta:" + sessionId + ":" + turnId;
    }

    private String curKey(String sessionId) {
        return keyPrefix + "cur:" + sessionId;
    }

    /** 登记新一轮：写 meta=running + startedAt + hb，并把 cur 指向该 turnId。 */
    public void register(String sessionId, String turnId) {
        long now = System.currentTimeMillis() / 1000;
        String meta = metaKey(sessionId, turnId);
        redis.opsForHash().putAll(meta, Map.of(
                "state", STATE_RUNNING,
                "startedAt", String.valueOf(now),
                "hb", String.valueOf(now)));
        redis.expire(meta, ttl); // 30m
        redis.opsForValue().set(curKey(sessionId), turnId, ttl);
    }

    /** 追加一条事件 JSON，并顺带刷新 TTL（心跳由 {@link #heartbeat} 定期补）。 */
    public void append(String sessionId, String turnId, String payloadJson) {
        String events = eventsKey(sessionId, turnId);
        redis.opsForList().rightPush(events, payloadJson);
        redis.expire(events, ttl);
    }

    /** 刷新心跳与 TTL，供 tail 侧判定本轮是否仍存活。 */
    public void heartbeat(String sessionId, String turnId) {
        long now = System.currentTimeMillis() / 1000;
        String meta = metaKey(sessionId, turnId);
        redis.opsForHash().put(meta, "hb", String.valueOf(now));
        redis.expire(meta, ttl);
        redis.expire(eventsKey(sessionId, turnId), ttl);
        redis.expire(curKey(sessionId), ttl);
    }

    public void markDone(String sessionId, String turnId) {
        setState(sessionId, turnId, STATE_DONE);
    }

    public void markFailed(String sessionId, String turnId) {
        setState(sessionId, turnId, STATE_FAILED);
    }

    private void setState(String sessionId, String turnId, String state) {
        String meta = metaKey(sessionId, turnId);
        redis.opsForHash().put(meta, "state", state);
        redis.expire(meta, ttl);
    }

    /** 读取一轮的状态；running 但心跳超时则判为 stale，无记录则为 none。 */
    public TurnStatus status(String sessionId, String turnId) {
        if (turnId == null || turnId.isBlank()) {
            return new TurnStatus(STATE_NONE, null, 0);
        }
        Map<Object, Object> meta = redis.opsForHash().entries(metaKey(sessionId, turnId));
        if (meta.isEmpty()) {
            return new TurnStatus(STATE_NONE, turnId, 0);
        }
        String state = String.valueOf(meta.getOrDefault("state", STATE_NONE));
        long startedAt = parseLong(meta.get("startedAt"));
        if (STATE_RUNNING.equals(state)) {
            long hb = parseLong(meta.get("hb"));
            if (hb > 0 && System.currentTimeMillis() / 1000 - hb > staleAfterSeconds) {
                state = STATE_STALE;
            }
        }
        return new TurnStatus(state, turnId, startedAt);
    }

    /** 从 offset 起读取已缓冲的事件 JSON（LRANGE offset -1）。 */
    public List<String> range(String sessionId, String turnId, long offset) {
        return redis.opsForList().range(eventsKey(sessionId, turnId), offset, -1);
    }

    /** 解析某会话当前（最近一次）的 turnId，无则返回 null。 */
    public String currentTurn(String sessionId) {
        return redis.opsForValue().get(curKey(sessionId));
    }

    private long parseLong(Object value) {
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    public record TurnStatus(String state, String turnId, long startedAt) {
        public boolean running() {
            return STATE_RUNNING.equals(state);
        }
    }
}
