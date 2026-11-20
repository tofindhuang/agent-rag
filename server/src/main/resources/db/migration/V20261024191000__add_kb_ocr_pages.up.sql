-- Up-only migration (ADR-0009, ADR-0014). OCR page count for scanned PDFs;
-- 0 means the text layer was used (or OCR is disabled). Guarded for replay.
SET @ocr_pages_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'kb_documents'
      AND COLUMN_NAME = 'ocr_pages'
);
SET @ocr_pages_ddl := IF(
    @ocr_pages_exists = 0,
    'ALTER TABLE kb_documents ADD COLUMN ocr_pages INT NOT NULL DEFAULT 0 AFTER chunk_count',
    'SELECT 1'
);
PREPARE ocr_pages_stmt FROM @ocr_pages_ddl;
EXECUTE ocr_pages_stmt;
DEALLOCATE PREPARE ocr_pages_stmt;
