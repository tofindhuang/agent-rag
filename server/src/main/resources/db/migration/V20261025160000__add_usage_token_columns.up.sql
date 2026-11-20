-- Up-only migration (ADR-0009, ADR-0023). Run-level correlation and model
-- snapshot for trace/cost, plus cache token columns for accurate costing.
-- Each ALTER is guarded so a manual execution followed by a Flyway replay is
-- safe.
SET @runs_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'runs' AND COLUMN_NAME = 'request_id'
);
SET @runs_ddl := IF(@runs_exists = 0,
    'ALTER TABLE runs
       ADD COLUMN request_id VARCHAR(128) NULL AFTER gateway_run_id,
       ADD COLUMN model_id VARCHAR(200) NULL AFTER request_id,
       ADD COLUMN cache_read_tokens BIGINT NULL AFTER tokens_out,
       ADD COLUMN cache_write_tokens BIGINT NULL AFTER cache_read_tokens,
       ADD KEY idx_runs_request (request_id),
       ADD KEY idx_runs_turn (turn_id),
       ADD KEY idx_runs_started (started_at),
       ADD KEY idx_runs_status_started (status, started_at),
       ADD KEY idx_runs_model_started (model_id, started_at)',
    'SELECT 1');
PREPARE runs_stmt FROM @runs_ddl;
EXECUTE runs_stmt;
DEALLOCATE PREPARE runs_stmt;

SET @messages_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'messages'
      AND COLUMN_NAME = 'cache_read_tokens'
);
SET @messages_ddl := IF(@messages_exists = 0,
    'ALTER TABLE messages
       ADD COLUMN cache_read_tokens BIGINT NULL AFTER tokens_out,
       ADD COLUMN cache_write_tokens BIGINT NULL AFTER cache_read_tokens,
       ADD KEY idx_messages_created (created_at)',
    'SELECT 1');
PREPARE messages_stmt FROM @messages_ddl;
EXECUTE messages_stmt;
DEALLOCATE PREPARE messages_stmt;
