-- Up-only migration (ADR-0009, ADR-0032). The monthly quota sum walks one
-- user's sessions by time; the guard keeps a manual execution followed by a
-- Flyway replay safe.
SET @index_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'runs'
      AND INDEX_NAME = 'idx_runs_session_started'
);
SET @index_ddl := IF(@index_exists = 0,
    'ALTER TABLE runs ADD KEY idx_runs_session_started (session_id, started_at)',
    'SELECT 1');
PREPARE index_stmt FROM @index_ddl;
EXECUTE index_stmt;
DEALLOCATE PREPARE index_stmt;
