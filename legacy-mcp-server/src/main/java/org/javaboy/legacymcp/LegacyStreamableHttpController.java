package org.javaboy.legacymcp;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class LegacyStreamableHttpController {

    private static final String SESSION_HEADER = "MCP-Session-Id";
    private static final List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of(
            "2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

    private final LegacyMcpSessionStore sessionStore;

    public LegacyStreamableHttpController(LegacyMcpSessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    @PostMapping(path = "/mcp", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> handle(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = SESSION_HEADER, required = false) String sessionId) {
        String method = String.valueOf(request.getOrDefault("method", ""));
        Object id = request.get("id");
        if ("initialize".equals(method)) {
            return initialize(asMap(request.get("params")), id, sessionId);
        }

        LegacyMcpSessionStore.Session session = sessionStore.find(sessionId);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(error(id, -32001, "MCP session not found; initialize again"));
        }
        if ("notifications/initialized".equals(method)) {
            return ResponseEntity.accepted().build();
        }
        if ("tools/list".equals(method)) {
            return json(result(id, Map.of("tools", tools())));
        }
        if ("tools/call".equals(method)) {
            return json(callTool(id, asMap(request.get("params")), session));
        }
        return json(error(id, -32601, "Method not found: " + method));
    }

    @DeleteMapping(path = "/mcp")
    public ResponseEntity<Void> deleteSession(
            @RequestHeader(value = SESSION_HEADER, required = false) String sessionId) {
        return sessionStore.remove(sessionId) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    private ResponseEntity<?> initialize(Map<String, Object> params, Object id, String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            return json(error(id, -32600, "Initialize requests must not contain MCP-Session-Id"));
        }
        String requestedVersion = String.valueOf(params.getOrDefault("protocolVersion", ""));
        String protocolVersion = SUPPORTED_PROTOCOL_VERSIONS.contains(requestedVersion)
                ? requestedVersion : SUPPORTED_PROTOCOL_VERSIONS.getFirst();
        LegacyMcpSessionStore.Session session = sessionStore.create(protocolVersion);
        Map<String, Object> initializeResult = new LinkedHashMap<>();
        initializeResult.put("protocolVersion", protocolVersion);
        initializeResult.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        initializeResult.put("serverInfo", Map.of(
                "name", "legacy-streamable-http-test-server",
                "version", "1.0.0"));
        initializeResult.put("instructions", "This server intentionally stores sessions in memory for restart testing.");
        return ResponseEntity.ok()
                .header(SESSION_HEADER, session.id())
                .header("MCP-Protocol-Version", protocolVersion)
                .contentType(MediaType.APPLICATION_JSON)
                .body(result(id, initializeResult));
    }

    private Map<String, Object> callTool(
            Object id,
            Map<String, Object> params,
            LegacyMcpSessionStore.Session session) {
        session.callCount().incrementAndGet();
        String name = String.valueOf(params.getOrDefault("name", ""));
        Map<String, Object> arguments = asMap(params.get("arguments"));
        return switch (name) {
            case "echo" -> toolResult("echo=" + arguments.getOrDefault("message", ""));
            case "add" -> toolResult("sum=" + (asDouble(arguments.get("left")) + asDouble(arguments.get("right"))));
            case "session_info" -> toolResult("sessionId=" + session.id()
                    + ", serverInstanceId=" + sessionStore.getServerInstanceId()
                    + ", callCount=" + session.callCount().get());
            default -> toolError("Unknown tool: " + name);
        };
    }

    private List<Map<String, Object>> tools() {
        return List.of(
                tool("echo", "返回传入文本，用于验证基础 tools/call。", Map.of(
                        "message", Map.of("type", "string", "description", "需要原样返回的文本")), List.of("message")),
                tool("add", "计算两个数字之和。", Map.of(
                        "left", Map.of("type", "number"),
                        "right", Map.of("type", "number")), List.of("left", "right")),
                tool("session_info", "返回当前 session 与当前服务实例标识，用于验证重启后 session 失效。", Map.of(), List.of()));
    }

    private Map<String, Object> tool(
            String name,
            String description,
            Map<String, Object> properties,
            List<String> required) {
        return Map.of(
                "name", name,
                "description", description,
                "inputSchema", Map.of("type", "object", "properties", properties, "required", required),
                "annotations", Map.of("readOnlyHint", true));
    }

    private Map<String, Object> toolResult(String text) {
        return Map.of("content", List.of(Map.of("type", "text", "text", text)), "isError", false);
    }

    private Map<String, Object> toolError(String text) {
        return Map.of("content", List.of(Map.of("type", "text", "text", text)), "isError", true);
    }

    private ResponseEntity<Map<String, Object>> json(Map<String, Object> body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private Map<String, Object> result(Object id, Map<String, Object> value) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", value);
        return response;
    }

    private Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));
        return response;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0D;
    }
}
