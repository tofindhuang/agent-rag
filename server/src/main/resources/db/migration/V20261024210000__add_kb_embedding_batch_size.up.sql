-- Up-only migration (ADR-0009). Batch size 1 means the provider cannot be
-- trusted with multi-input embedding responses; guard keeps replay idempotent.
SET @batch_size_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'kb_user_settings'
      AND COLUMN_NAME = 'embedding_batch_size'
);
SET @batch_size_ddl := IF(
    @batch_size_exists = 0,
    'ALTER TABLE kb_user_settings ADD COLUMN embedding_batch_size INT NOT NULL DEFAULT 32 AFTER embedding_dim',
    'SELECT 1'
);
PREPARE batch_size_stmt FROM @batch_size_ddl;
EXECUTE batch_size_stmt;
DEALLOCATE PREPARE batch_size_stmt;
