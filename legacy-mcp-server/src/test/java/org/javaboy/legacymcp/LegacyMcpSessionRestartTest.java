package org.javaboy.legacymcp;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LegacyMcpSessionRestartTest {

    @Test
    void serverRestartInvalidatesInMemorySession() throws Exception {
        LegacyMcpSessionStore beforeRestart = new LegacyMcpSessionStore();
        LegacyMcpSessionStore.Session session = beforeRestart.create("2025-11-25");
        assertEquals(session, beforeRestart.find(session.id()));

        LegacyMcpSessionStore afterRestart = new LegacyMcpSessionStore();
        assertNull(afterRestart.find(session.id()));

        LegacyStreamableHttpController controller = new LegacyStreamableHttpController(afterRestart);
        ResponseEntity<?> response = controller.handle(
                Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list"),
                session.id());

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolCallReturnsJsonRpcResultEnvelope() {
        LegacyMcpSessionStore sessionStore = new LegacyMcpSessionStore();
        LegacyMcpSessionStore.Session session = sessionStore.create("2025-11-25");
        LegacyStreamableHttpController controller = new LegacyStreamableHttpController(sessionStore);

        ResponseEntity<?> response = controller.handle(Map.of(
                "jsonrpc", "2.0",
                "id", 3,
                "method", "tools/call",
                "params", Map.of("name", "add", "arguments", Map.of("left", 10_000, "right", 10_000))), session.id());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("2.0", body.get("jsonrpc"));
        assertEquals(3, body.get("id"));
        Map<String, Object> result = (Map<String, Object>) body.get("result");
        assertEquals(false, result.get("isError"));
    }
}
