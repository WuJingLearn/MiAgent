package org.javaboy.backend.service;

import org.javaboy.backend.agent.ModelCatalog;
import org.javaboy.backend.domain.ChatSession;
import org.javaboy.backend.repository.ChatSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatSessionServiceTests {

    @Test
    void fixesSelectedModelWhenCreatingSession() {
        ChatSessionRepository repository = mock(ChatSessionRepository.class);
        ModelCatalog catalog = mock(ModelCatalog.class);
        when(catalog.contains("qwen-max")).thenReturn(true);
        ChatSessionService service = new ChatSessionService(repository, catalog);

        ChatSession session = service.create("user-1", "qwen-max", "线上告警");

        assertEquals("qwen-max", session.modelKey());
        assertEquals("user-1", session.userId());
        verify(repository).insert(any(ChatSession.class));
    }

    @Test
    void rejectsUnavailableModel() {
        ChatSessionRepository repository = mock(ChatSessionRepository.class);
        ModelCatalog catalog = mock(ModelCatalog.class);
        ChatSessionService service = new ChatSessionService(repository, catalog);

        assertThrows(ResponseStatusException.class,
                () -> service.create("user-1", "unknown", "test"));
    }
}
