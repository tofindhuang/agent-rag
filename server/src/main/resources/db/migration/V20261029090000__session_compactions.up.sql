-- Compaction summaries per session (ADR-0028): conversation content, so it is
-- kept out of compaction_events (FR-7 admin metrics) on purpose.
CREATE TABLE IF NOT EXISTS session_compactions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id CHAR(36) NOT NULL,
    after_message_id BIGINT NULL,
    reason VARCHAR(16) NULL,
    summary MEDIUMTEXT NOT NULL,
    truncated TINYINT(1) NOT NULL DEFAULT 0,
    tokens_before BIGINT NULL,
    tokens_after BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_session_compactions_session (session_id, id),
    CONSTRAINT fk_session_compactions_session FOREIGN KEY (session_id)
        REFERENCES sessions (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
