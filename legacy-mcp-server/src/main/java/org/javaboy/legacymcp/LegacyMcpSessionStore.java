package org.javaboy.legacymcp;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class LegacyMcpSessionStore {

    private final String serverInstanceId = UUID.randomUUID().toString();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public Session create(String protocolVersion) {
        String sessionId = UUID.randomUUID().toString();
        Session session = new Session(sessionId, protocolVersion, Instant.now(), new AtomicInteger());
        sessions.put(sessionId, session);
        return session;
    }

    public Session find(String sessionId) {
        return sessionId == null ? null : sessions.get(sessionId);
    }

    public boolean remove(String sessionId) {
        return sessionId != null && sessions.remove(sessionId) != null;
    }

    public String getServerInstanceId() {
        return serverInstanceId;
    }

    public record Session(
            String id,
            String protocolVersion,
            Instant createdAt,
            AtomicInteger callCount) {
    }
}
