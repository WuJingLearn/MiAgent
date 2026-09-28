package org.javaboy.backend.repository;

import org.javaboy.backend.domain.DiagnosisTask;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public class DiagnosisRepository {

    private final JdbcTemplate jdbcTemplate;

    public DiagnosisRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public String createTaskIfAbsent(
            String alertEventId,
            String alertKey,
            String title,
            String content,
            String taskId,
            String userId,
            String sessionId,
            String modelKey) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO alert_event
                        (alert_event_id, alert_key, title, content, created_at, updated_at)
                    VALUES (?, ?, ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                    """, alertEventId, alertKey, title, content);
            jdbcTemplate.update("""
                    INSERT INTO diagnosis_task
                        (task_id, alert_event_id, user_id, session_id, model_key, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 'RECEIVED', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                    """, taskId, alertEventId, userId, sessionId, modelKey);
            return taskId;
        } catch (DuplicateKeyException duplicate) {
            return jdbcTemplate.queryForObject("""
                    SELECT t.task_id
                    FROM diagnosis_task t
                    JOIN alert_event a ON a.alert_event_id = t.alert_event_id
                    WHERE a.alert_key = ?
                    """, String.class, alertKey);
        }
    }

    public boolean markRunning(String taskId) {
        return jdbcTemplate.update("""
                UPDATE diagnosis_task
                SET status = 'DIAGNOSING', updated_at = UTC_TIMESTAMP(3)
                WHERE task_id = ? AND status = 'RECEIVED'
                """, taskId) == 1;
    }

    public void markDone(String taskId, String report) {
        jdbcTemplate.update("""
                UPDATE diagnosis_task
                SET status = 'DONE', report = ?, error_message = NULL, updated_at = UTC_TIMESTAMP(3)
                WHERE task_id = ?
                """, report, taskId);
    }

    public void markFailed(String taskId, String errorMessage) {
        jdbcTemplate.update("""
                UPDATE diagnosis_task
                SET status = 'FAILED', error_message = ?, updated_at = UTC_TIMESTAMP(3)
                WHERE task_id = ?
                """, abbreviate(errorMessage, 2000), taskId);
    }

    public Optional<DiagnosisTask> findById(String taskId) {
        List<DiagnosisTask> result = jdbcTemplate.query("""
                        SELECT task_id, alert_event_id, user_id, session_id, model_key,
                               status, report, error_message, created_at, updated_at
                        FROM diagnosis_task
                        WHERE task_id = ?
                        """,
                (rs, rowNum) -> new DiagnosisTask(
                        rs.getString("task_id"),
                        rs.getString("alert_event_id"),
                        rs.getString("user_id"),
                        rs.getString("session_id"),
                        rs.getString("model_key"),
                        rs.getString("status"),
                        rs.getString("report"),
                        rs.getString("error_message"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                taskId);
        return result.stream().findFirst();
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
