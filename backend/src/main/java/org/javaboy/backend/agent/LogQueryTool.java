package org.javaboy.backend.agent;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 演示用的「服务日志查询」工具。
 *
 * <p>它会为指定服务合成一段体量可控的运行日志，用来演示 Harness 的
 * <b>大工具结果卸载（tool result eviction）</b>：当单次工具返回超过阈值（默认 80K 字符）时，
 * 框架会把全文落盘到工作区的 {@code large_tool_results/} 目录，上下文里只保留首尾预览 + 占位符，
 * agent 需要全文时再用 {@code read_file} 回读。
 *
 * <p>默认 {@code lines=2000}，单条返回约 20 万字符，足以稳定触发卸载。把 {@code lines} 调小
 * （例如 50）则不会触发，可用于对比。
 */
@Component
public class LogQueryTool {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    private static final String[] LEVELS = {"INFO", "INFO", "INFO", "WARN", "ERROR", "DEBUG"};

    private static final String[] MESSAGES = {
            "request accepted, routing to backend deployment",
            "model forward pass completed, tokens=%d latency=%dms",
            "throttled by rate limiter, retry-after=%dms bucket=default",
            "upstream connection reset, will retry attempt=%d",
            "cache miss for prompt fingerprint, falling back to cold path",
            "GPU memory pressure detected, utilization=%d%% pool=L20D",
            "EngineAbort while decoding, request will be re-queued",
            "streaming chunk flushed to client, seq=%d bytes=%d",
            "context length exceeded, triggering emergency compaction",
            "health probe ok, inflight=%d queue_depth=%d",
    };

    @Tool(
            name = "query_service_logs",
            description = "查询指定后端服务最近一段时间的原始运行日志。返回完整日志文本，"
                    + "体量可能非常大（可达数十万字符）。可用 keyword 过滤、lines 控制返回行数。",
            readOnly = true)
    public String queryServiceLogs(
            @ToolParam(name = "service", description = "服务名，例如 inference-gateway / model-router") String service,
            @ToolParam(name = "keyword", required = false, description = "只返回包含该关键词的日志行，可选") String keyword,
            @ToolParam(name = "lines", required = false, description = "返回的日志行数，默认 2000（足以演示大结果卸载）") Integer lines) {

        String svc = (service == null || service.isBlank()) ? "inference-gateway" : service.trim();
        int total = (lines == null || lines <= 0) ? 2000 : Math.min(lines, 200_000);

        LocalDateTime base = LocalDateTime.of(2026, 9, 27, 10, 0, 0);
        StringBuilder sb = new StringBuilder(total * 110);
        int emitted = 0;
        for (int i = 0; i < total; i++) {
            String level = LEVELS[i % LEVELS.length];
            String template = MESSAGES[i % MESSAGES.length];
            String msg = formatMessage(template, i);
            String reqId = String.format("req-%08x", (svc.hashCode() ^ (i * 2654435761L)) & 0xffffffffL);
            String line = String.format("%s [%s] [reqId=%s] [thread-%d] %-5s %s",
                    TS.format(base.plusSeconds(i)), svc, reqId, i % 16, level, msg);

            if (keyword != null && !keyword.isBlank() && !line.contains(keyword)) {
                continue;
            }
            sb.append(line).append('\n');
            emitted++;
        }

        String header = String.format(
                "==== service=%s lines_requested=%d lines_returned=%d%s ====%n",
                svc, total, emitted, (keyword == null || keyword.isBlank()) ? "" : " keyword=" + keyword);
        return header + sb;
    }

    private String formatMessage(String template, int i) {
        long count = template.chars().filter(c -> c == '%').count();
        if (count == 0) {
            return template;
        }
        Object[] args = new Object[(int) count];
        for (int k = 0; k < args.length; k++) {
            args[k] = 100 + (i * 37 + k * 13) % 9000;
        }
        try {
            return String.format(template, args);
        } catch (RuntimeException ex) {
            return template;
        }
    }

    /** 便于单测直接验证结果体量。 */
    public static List<String> levels() {
        return List.of(LEVELS);
    }
}
