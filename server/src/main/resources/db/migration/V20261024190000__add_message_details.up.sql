-- Up-only migration (ADR-0009, ADR-0015). Guard keeps a manual apply and a
-- later Flyway replay idempotent; MySQL has no ADD COLUMN IF NOT EXISTS.
SET @details_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'messages'
      AND COLUMN_NAME = 'details'
);
SET @details_ddl := IF(
    @details_exists = 0,
    'ALTER TABLE messages ADD COLUMN details JSON NULL AFTER tool_calls',
    'SELECT 1'
);
PREPARE details_stmt FROM @details_ddl;
EXECUTE details_stmt;
DEALLOCATE PREPARE details_stmt;
