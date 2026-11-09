-- Up-only migration (ADR-0009, ADR-0035). Branching hides, never deletes, the
-- abandoned tail; the pi entry id is the only safe way to address a branch
-- point in the engine's session tree. Guards keep a manual replay safe.
SET @branch_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'messages'
      AND COLUMN_NAME = 'active_branch'
);
SET @branch_ddl := IF(@branch_exists = 0,
    'ALTER TABLE messages
       ADD COLUMN pi_entry_id VARCHAR(64) NULL AFTER details,
       ADD COLUMN active_branch BOOLEAN NOT NULL DEFAULT TRUE AFTER pi_entry_id,
       ADD KEY idx_messages_session_active (session_id, active_branch, id)',
    'SELECT 1');
PREPARE branch_stmt FROM @branch_ddl;
EXECUTE branch_stmt;
DEALLOCATE PREPARE branch_stmt;
