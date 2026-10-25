-- Up-only migration (ADR-0009, ADR-0016, ADR-0022). Embedding fingerprint for
-- the retrieval guard: every successfully ingested document records which
-- model and dimension produced its vectors. `embedded_at` marks "this document
-- may have vectors in Qdrant" so a fresh upload never blocks retrieval while
-- it is still queued. The guards make a manual execution followed by a Flyway
-- replay safe.
SET @fingerprint_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_documents'
      AND COLUMN_NAME = 'embedding_model'
);
SET @fingerprint_created := (@fingerprint_exists = 0);
SET @fingerprint_ddl := IF(@fingerprint_exists = 0,
    'ALTER TABLE kb_documents
       ADD COLUMN embedding_model VARCHAR(255) NULL AFTER chunk_count,
       ADD COLUMN embedding_dim INT NULL AFTER embedding_model,
       ADD COLUMN embedded_at DATETIME(6) NULL AFTER embedding_dim',
    'SELECT 1');
PREPARE fingerprint_stmt FROM @fingerprint_ddl;
EXECUTE fingerprint_stmt;
DEALLOCATE PREPARE fingerprint_stmt;

-- Documents that existed before this migration were ingested without a
-- fingerprint: mark them as "may have vectors" so the guard blocks retrieval
-- until reingest-all proves their model. Only the creating run backfills;
-- rows inserted later start NULL.
UPDATE kb_documents
SET embedded_at = updated_at
WHERE @fingerprint_created = 1
  AND embedded_at IS NULL
  AND status <> 'deleting';
