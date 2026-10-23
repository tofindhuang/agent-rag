-- Up-only migration (ADR-0009). Message projection and run ledger (ADR-0006).
CREATE TABLE IF NOT EXISTS runs (
    id CHAR(36) NOT NULL,
    session_id CHAR(36) NOT NULL,
    turn_id CHAR(36) NOT NULL,
    gateway_run_id CHAR(36) NULL,
    status ENUM('running', 'completed', 'aborted', 'failed') NOT NULL DEFAULT 'running',
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(1024) NULL,
    tokens_in BIGINT NULL,
    tokens_out BIGINT NULL,
    started_at DATETIME(6) NOT NULL,
    settled_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_runs_session_turn (session_id, turn_id),
    CONSTRAINT fk_runs_session FOREIGN KEY (session_id) REFERENCES sessions (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS messages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id CHAR(36) NOT NULL,
    turn_id CHAR(36) NOT NULL,
    ordinal INT NOT NULL,
    role ENUM('user', 'assistant', 'tool') NOT NULL,
    content MEDIUMTEXT NULL,
    thinking MEDIUMTEXT NULL,
    tool_calls JSON NULL,
    status ENUM('complete', 'partial', 'error') NULL,
    delivery_status ENUM('pending', 'sent', 'failed') NULL,
    run_id CHAR(36) NULL,
    assistant_seq_in_turn INT NULL,
    tokens_in BIGINT NULL,
    tokens_out BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_messages_turn_ordinal (session_id, turn_id, ordinal),
    KEY idx_messages_session_id (session_id, id),
    CONSTRAINT fk_messages_session FOREIGN KEY (session_id) REFERENCES sessions (id),
    CONSTRAINT fk_messages_run FOREIGN KEY (run_id) REFERENCES runs (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
