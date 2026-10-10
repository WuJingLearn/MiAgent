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
}
