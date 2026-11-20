-- Up-only migration (ADR-0009, ADR-0023). Read-only admin role for the
-- observability dashboard. The guard makes a manual execution followed by a
-- Flyway replay safe.
SET @role_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'role'
);
SET @role_ddl := IF(@role_exists = 0,
    "ALTER TABLE users ADD COLUMN role ENUM('user','admin') NOT NULL DEFAULT 'user' AFTER password_hash",
    'SELECT 1');
PREPARE role_stmt FROM @role_ddl;
EXECUTE role_stmt;
DEALLOCATE PREPARE role_stmt;
