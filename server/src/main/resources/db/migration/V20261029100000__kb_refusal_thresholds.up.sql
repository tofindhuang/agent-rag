-- Up-only migration (ADR-0009, ADR-0029). Refusal thresholds live next to the
-- embedding/rerank settings because each value belongs to that model's score
-- scale. NULL means "do not refuse"; the guard keeps a manual execution
-- followed by a Flyway replay safe.
SET @threshold_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_user_settings'
      AND COLUMN_NAME = 'rerank_min_score'
);
SET @threshold_ddl := IF(@threshold_exists = 0,
    'ALTER TABLE kb_user_settings
       ADD COLUMN rerank_min_score DECIMAL(8,6) NULL AFTER rerank_path,
       ADD COLUMN vector_min_score DECIMAL(8,6) NULL AFTER rerank_min_score',
    'SELECT 1');
PREPARE threshold_stmt FROM @threshold_ddl;
EXECUTE threshold_stmt;
DEALLOCATE PREPARE threshold_stmt;
