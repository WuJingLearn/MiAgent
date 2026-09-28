package org.javaboy.backend.repository;

import org.javaboy.backend.domain.ChatSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class ChatSessionRepository {

    private final JdbcTemplate jdbcTemplate;

    public ChatSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(ChatSession session) {
        jdbcTemplate.update("""
                INSERT INTO chat_session
                    (session_id, user_id, model_key, title, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                session.sessionId(), session.userId(), session.modelKey(), session.title(), session.status(),
                Timestamp.from(session.createdAt()), Timestamp.from(session.updatedAt()));
    }

    public Optional<ChatSession> findOwnedSession(String sessionId, String userId) {
        List<ChatSession> result = jdbcTemplate.query("""
                        SELECT session_id, user_id, model_key, title, status, created_at, updated_at
                        FROM chat_session
                        WHERE session_id = ? AND user_id = ?
                        """,
                (rs, rowNum) -> new ChatSession(
                        rs.getString("session_id"),
                        rs.getString("user_id"),
                        rs.getString("model_key"),
                        rs.getString("title"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                sessionId, userId);
        return result.stream().findFirst();
    }

    public List<ChatSession> listByUser(String userId) {
        return jdbcTemplate.query("""
                        SELECT session_id, user_id, model_key, title, status, created_at, updated_at
                        FROM chat_session
                        WHERE user_id = ?
                        ORDER BY updated_at DESC
                        LIMIT 100
                        """,
                (rs, rowNum) -> new ChatSession(
                        rs.getString("session_id"),
                        rs.getString("user_id"),
                        rs.getString("model_key"),
                        rs.getString("title"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                userId);
    }

    public boolean startTurn(String sessionId, String requestId) {
        int inserted = jdbcTemplate.update("""
                INSERT IGNORE INTO chat_turn
                    (session_id, request_id, status, created_at, updated_at)
                VALUES (?, ?, 'RUNNING', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, sessionId, requestId);
        return inserted == 1;
    }

    public void completeTurn(String sessionId, String requestId, String response) {
        jdbcTemplate.update("""
                UPDATE chat_turn
                SET status = 'DONE', response_text = ?, updated_at = UTC_TIMESTAMP(3)
                WHERE session_id = ? AND request_id = ?
                """, response, sessionId, requestId);
        jdbcTemplate.update(
                "UPDATE chat_session SET updated_at = UTC_TIMESTAMP(3) WHERE session_id = ?",
                sessionId);
    }

    public void failTurn(String sessionId, String requestId, String errorMessage) {
        jdbcTemplate.update("""
                UPDATE chat_turn
                SET status = 'FAILED', error_message = ?, updated_at = UTC_TIMESTAMP(3)
                WHERE session_id = ? AND request_id = ?
                """, abbreviate(errorMessage, 2000), sessionId, requestId);
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
