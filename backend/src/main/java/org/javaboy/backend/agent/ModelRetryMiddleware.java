package org.javaboy.backend.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ModelHttpException;
import io.agentscope.core.model.transport.HttpTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

/**
 * 模型调用重试中间件。
 *
 * <p>拦截原始模型 API 调用，在发生可重试错误时按策略延迟重试。
 * 重试逻辑参考 paas-manager 中基于 Eino ADK 的实现：
 * <ul>
 *   <li>可重试：HTTP 429/500/502/503、限流错误、以及错误消息命中 transient 关键字的异常。</li>
 *   <li>不可重试：HTTP 400（除非含 transient 错误码/消息）、401、403、404、409 等明确客户端错误。</li>
 *   <li>按 RPM/TPM/BurstRate 分档使用不同初始/最大退避时间。</li>
 *   <li>熔断：TPM 类型重试超过 2 次、RPM 类型重试超过 3 次后停止重试。</li>
 * </ul>
 *
 * <p>该中间件的 {@link #order()} 大于 {@link ModelRoutingMiddleware} 的默认 order，
 * 因此位于洋葱模型更外层：先进入重试逻辑，再进入模型路由，最终到达真实模型调用。
 */
public class ModelRetryMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ModelRetryMiddleware.class);

    private static final int DEFAULT_MAX_RETRIES = 6;

    private static final Set<Integer> NON_RETRYABLE_HTTP_STATUSES = Set.of(400, 401, 403, 404, 409);
    private static final Set<Integer> RETRYABLE_HTTP_STATUSES = Set.of(429, 500, 502, 503);

    private static final List<String> RPM_CODES = List.of(
            "limit_requests", "ratequota", "limitrequests");
    private static final List<String> RPM_MESSAGES = List.of(
            "requests rate limit exceeded",
            "exceeded your current requests",
            "requests throttling triggered",
            "too many requests in route");

    private static final List<String> TPM_CODES = List.of(
            "allocationquota", "insufficient_quota");
    private static final List<String> TPM_MESSAGES = List.of(
            "allocated quota exceeded",
            "exceeded your current quota");

    private static final List<String> BURST_RATE_CODES = List.of(
            "burstrate", "limit_burst_rate");
    private static final List<String> BURST_RATE_MESSAGES = List.of(
            "rate increased too quickly",
            "request rate increased too quickly");

    private static final List<String> TRANSIENT_ERROR_CODES = List.of(
            "internalerror", "server_error", "internal_error",
            "systemerror", "modelservicefailed", "modelunavailable",
            "modelservingerror", "throttling");

    private static final List<String> TRANSIENT_ERROR_MESSAGES = List.of(
            "internal server error", "bad gateway", "service unavailable",
            "server_error", "internal_error", "model is unavailable",
            "failed to request model service", "backend buffer overflow",
            "too many requests", "throttled", "qpm limit");

    private final boolean enabled;
    private final int maxRetries;
    private final Random random = new Random();

    public ModelRetryMiddleware() {
        this(true, DEFAULT_MAX_RETRIES);
    }

    public ModelRetryMiddleware(int maxRetries) {
        this(true, maxRetries);
    }

    public ModelRetryMiddleware(boolean enabled, int maxRetries) {
        if (maxRetries <= 0) {
            throw new IllegalArgumentException("maxRetries must be > 0: " + maxRetries);
        }
        this.enabled = enabled;
        this.maxRetries = maxRetries;
    }

    @Override
    public int order() {
        // 大于 ModelRoutingMiddleware 的默认 order(1)，使其位于更外层先执行
        return 10;
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext context,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {

        if (!enabled) {
            return next.apply(input);
        }

        return next.apply(input)
                .retryWhen(Retry.from(companion -> companion.flatMap(retrySignal -> {
                    Throwable error = retrySignal.failure();
                    int attempt = (int) (retrySignal.totalRetries() + 1);
                    RetryDecision decision = decide(error, attempt);
                    if (decision.retry) {
                        log.warn("模型调用失败，第 {} 次重试，退避 {} ms，错误：{}",
                                attempt, decision.backoff.toMillis(), error.getMessage());
                        return Mono.delay(decision.backoff);
                    }
                    return Mono.error(error);
                })));
    }

    private RetryDecision decide(Throwable error, int attempt) {
        if (!isModelRetryable(error)) {
            return RetryDecision.noRetry();
        }

        RateLimitType rateLimitType = classifyRateLimit(error);

        // 熔断：TPM 超过 2 次、RPM 超过 3 次后停止重试
        if (rateLimitType == RateLimitType.TPM && attempt > 2) {
            log.debug("TPM 限流已重试 {} 次，停止重试", attempt);
            return RetryDecision.noRetry();
        }
        if (rateLimitType == RateLimitType.RPM && attempt > 3) {
            log.debug("RPM 限流已重试 {} 次，停止重试", attempt);
            return RetryDecision.noRetry();
        }

        if (attempt > maxRetries) {
            log.debug("已达最大重试次数 {}，停止重试", maxRetries);
            return RetryDecision.noRetry();
        }

        Duration backoff = rateLimitBackoff(rateLimitType, attempt);
        return RetryDecision.retry(backoff);
    }

    private boolean isModelRetryable(Throwable error) {
        int status = extractHttpStatus(error);
        if (RETRYABLE_HTTP_STATUSES.contains(status)) {
            return true;
        }
        if (classifyRateLimit(error) != RateLimitType.NONE) {
            return true;
        }

        // 部分模型服务异常被错误地包装为 HTTP 400，需要按错误码/消息识别；
        // 但 401/403/404/409 等明确客户端错误仍保持不可重试。
        if (status != 400 && NON_RETRYABLE_HTTP_STATUSES.contains(status)) {
            return false;
        }
        return containsTransientError(error);
    }

    private int extractHttpStatus(Throwable error) {
        if (error instanceof HttpTransportException hte && hte.getStatusCode() != null) {
            return hte.getStatusCode();
        }
        if (error instanceof ModelHttpException mhe && mhe.getStatusCode() != null) {
            return mhe.getStatusCode();
        }
        Throwable cause = error.getCause();
        if (cause != null && cause != error) {
            return extractHttpStatus(cause);
        }
        return 0;
    }

    private RateLimitType classifyRateLimit(Throwable error) {
        String code = extractCode(error).toLowerCase();
        String message = extractMessage(error).toLowerCase();

        if (matchAny(code, RPM_CODES) || matchAny(message, RPM_MESSAGES)) {
            return RateLimitType.RPM;
        }
        if (matchAny(code, TPM_CODES) || matchAny(message, TPM_MESSAGES)) {
            return RateLimitType.TPM;
        }
        if (matchAny(code, BURST_RATE_CODES) || matchAny(message, BURST_RATE_MESSAGES)) {
            return RateLimitType.BURST_RATE;
        }
        return RateLimitType.NONE;
    }

    private String extractCode(Throwable error) {
        String message = error.getMessage();
        if (message == null) {
            return "";
        }
        // 常见格式：Code: InternalError.Algo 或 "Code":"InternalError.Algo"
        int idx = message.indexOf("Code:");
        if (idx >= 0) {
            return message.substring(idx + 5).trim().split("[\\s\\,\\}]+")[0];
        }
        idx = message.indexOf("\"Code\"");
        if (idx >= 0) {
            int start = message.indexOf('"', idx + 6);
            if (start >= 0) {
                int end = message.indexOf('"', start + 1);
                if (end > start) {
                    return message.substring(start + 1, end);
                }
            }
        }
        return "";
    }

    private String extractMessage(Throwable error) {
        String message = error.getMessage();
        return message != null ? message : "";
    }

    private boolean containsTransientError(Throwable error) {
        String code = extractCode(error).toLowerCase();
        String message = extractMessage(error).toLowerCase();
        return matchAny(code, TRANSIENT_ERROR_CODES) || matchAny(message, TRANSIENT_ERROR_MESSAGES);
    }

    private boolean matchAny(String text, List<String> patterns) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String pattern : patterns) {
            if (text.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private Duration rateLimitBackoff(RateLimitType rateLimitType, int attempt) {
        return switch (rateLimitType) {
            case RPM -> clampedExponentialBackoff(Duration.ofSeconds(3), Duration.ofSeconds(15), attempt);
            case TPM -> clampedExponentialBackoff(Duration.ofSeconds(15), Duration.ofSeconds(60), attempt);
            case BURST_RATE -> clampedExponentialBackoff(Duration.ofSeconds(5), Duration.ofSeconds(30), attempt);
            default -> clampedExponentialBackoff(Duration.ofMillis(100), Duration.ofSeconds(10), attempt);
        };
    }

    private Duration clampedExponentialBackoff(Duration base, Duration maxDelay, int attempt) {
        long delayNanos = base.toNanos();
        for (int i = 0; i < Math.max(0, attempt - 1); i++) {
            delayNanos *= 2;
            if (delayNanos > maxDelay.toNanos()) {
                delayNanos = maxDelay.toNanos();
                break;
            }
        }
        long jitter = (long) (delayNanos / 3.0 * random.nextDouble());
        return Duration.ofNanos(delayNanos + jitter);
    }

    private enum RateLimitType {
        NONE, RPM, TPM, BURST_RATE
    }

    private record RetryDecision(boolean retry, Duration backoff) {
        static RetryDecision retry(Duration backoff) {
            return new RetryDecision(true, backoff);
        }

        static RetryDecision noRetry() {
            return new RetryDecision(false, null);
        }
    }
}
