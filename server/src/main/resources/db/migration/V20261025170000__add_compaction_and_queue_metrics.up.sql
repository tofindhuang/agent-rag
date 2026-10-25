-- Up-only migration (ADR-0009, ADR-0023). Sandbox queue wait and compaction
-- events power the admin overview. Guards keep manual replay safe.
SET @queue_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exec_records'
      AND COLUMN_NAME = 'queue_ms'
);
SET @queue_ddl := IF(@queue_exists = 0,
    'ALTER TABLE exec_records ADD COLUMN queue_ms INT NULL AFTER duration_ms',
    'SELECT 1');
PREPARE queue_stmt FROM @queue_ddl;
EXECUTE queue_stmt;
DEALLOCATE PREPARE queue_stmt;

CREATE TABLE IF NOT EXISTS compaction_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    session_id CHAR(36) NOT NULL,
    run_id CHAR(36) NULL,
    turn_id CHAR(36) NULL,
    request_id VARCHAR(128) NULL,
    reason VARCHAR(64) NULL,
    tokens_before BIGINT NULL,
    tokens_after BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_compaction_user_created (user_id, created_at),
    KEY idx_compaction_session_created (session_id, created_at),
    CONSTRAINT fk_compaction_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_compaction_session FOREIGN KEY (session_id)
        REFERENCES sessions (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
