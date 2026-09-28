CREATE TABLE IF NOT EXISTS chat_session (
    session_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    model_key VARCHAR(64) NOT NULL,
    title VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (session_id),
    KEY idx_chat_session_user_updated (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chat_turn (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL,
    response_text LONGTEXT NULL,
    error_message VARCHAR(2000) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_turn_request (session_id, request_id),
    CONSTRAINT fk_chat_turn_session
        FOREIGN KEY (session_id) REFERENCES chat_session(session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS alert_event (
    alert_event_id VARCHAR(64) NOT NULL,
    alert_key VARCHAR(255) NOT NULL,
    title VARCHAR(500) NOT NULL,
    content LONGTEXT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (alert_event_id),
    UNIQUE KEY uk_alert_event_key (alert_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS diagnosis_task (
    task_id VARCHAR(64) NOT NULL,
    alert_event_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(255) NOT NULL,
    model_key VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    report LONGTEXT NULL,
    error_message VARCHAR(2000) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (task_id),
    UNIQUE KEY uk_diagnosis_alert (alert_event_id),
    KEY idx_diagnosis_status_updated (status, updated_at),
    CONSTRAINT fk_diagnosis_alert
        FOREIGN KEY (alert_event_id) REFERENCES alert_event(alert_event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
