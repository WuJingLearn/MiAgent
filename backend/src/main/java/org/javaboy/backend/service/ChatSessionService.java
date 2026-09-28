package org.javaboy.backend.service;

import org.javaboy.backend.agent.ModelCatalog;
import org.javaboy.backend.domain.ChatSession;
import org.javaboy.backend.repository.ChatSessionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ChatSessionService {

    private final ChatSessionRepository repository;
    private final ModelCatalog modelCatalog;

    public ChatSessionService(ChatSessionRepository repository, ModelCatalog modelCatalog) {
        this.repository = repository;
        this.modelCatalog = modelCatalog;
    }

    public ChatSession create(String userId, String modelKey, String title) {
        if (!modelCatalog.contains(modelKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模型不存在或不可用: " + modelKey);
        }
        Instant now = Instant.now();
        ChatSession session = new ChatSession(
                UUID.randomUUID().toString(),
                userId,
                modelKey,
                normalizeTitle(title),
                "OPEN",
                now,
                now);
        repository.insert(session);
        return session;
    }

    public ChatSession requireOwned(String sessionId, String userId) {
        return repository.findOwnedSession(sessionId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
    }

    public List<ChatSession> list(String userId) {
        return repository.listByUser(userId);
    }

    private String normalizeTitle(String title) {
        return title == null || title.isBlank() ? "新建诊断" : title.trim();
    }
}
