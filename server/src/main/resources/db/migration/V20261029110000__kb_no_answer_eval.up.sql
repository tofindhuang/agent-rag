-- Up-only migration (ADR-0009, ADR-0029). Evaluation labels gain a type so a
-- knowledge base can declare "this question has no answer here"; the target
-- columns become nullable for those labels. Runs record the refusal metric and
-- the threshold that produced it. Guards keep a manual execution followed by a
-- Flyway replay safe.
SET @label_expectation_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_eval_labels'
      AND COLUMN_NAME = 'expectation'
);
SET @label_ddl := IF(@label_expectation_exists = 0,
    'ALTER TABLE kb_eval_labels
       ADD COLUMN expectation ENUM(''answer'', ''no_answer'') NOT NULL DEFAULT ''answer''
           AFTER query,
       MODIFY COLUMN document_id CHAR(36) NULL,
       MODIFY COLUMN seq INT NULL,
       MODIFY COLUMN passage_sha256 CHAR(64) COLLATE utf8mb4_0900_as_cs NULL',
    'SELECT 1');
PREPARE label_stmt FROM @label_ddl;
EXECUTE label_stmt;
DEALLOCATE PREPARE label_stmt;

SET @run_refusal_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_eval_runs'
      AND COLUMN_NAME = 'refusal_accuracy'
);
SET @run_ddl := IF(@run_refusal_exists = 0,
    'ALTER TABLE kb_eval_runs
       ADD COLUMN no_answer_count INT NOT NULL DEFAULT 0 AFTER label_count,
       ADD COLUMN refusal_accuracy DECIMAL(6,4) NULL AFTER baseline_mrr5,
       ADD COLUMN threshold_used DECIMAL(8,6) NULL AFTER refusal_accuracy,
       ADD COLUMN threshold_source VARCHAR(16) NULL AFTER threshold_used',
    'SELECT 1');
PREPARE run_stmt FROM @run_ddl;
EXECUTE run_stmt;
DEALLOCATE PREPARE run_stmt;
