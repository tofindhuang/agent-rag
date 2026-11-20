-- Up-only migration (ADR-0009, ADR-0016). Default KB backfill is guarded so a
-- manual execution followed by a Flyway replay is safe.
CREATE TABLE IF NOT EXISTS knowledge_bases (
    id CHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_bases_user_name (user_id, name),
    KEY idx_knowledge_bases_user_default (user_id, is_default),
    CONSTRAINT fk_knowledge_bases_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS session_knowledge_bases (
    session_id CHAR(36) NOT NULL,
    kb_id CHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (session_id, kb_id),
    KEY idx_session_knowledge_bases_kb (kb_id),
    CONSTRAINT fk_session_knowledge_bases_session FOREIGN KEY (session_id)
        REFERENCES sessions (id) ON DELETE CASCADE,
    CONSTRAINT fk_session_knowledge_bases_kb FOREIGN KEY (kb_id)
        REFERENCES knowledge_bases (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

SET @kb_col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_documents' AND COLUMN_NAME = 'kb_id'
);
SET @kb_col_ddl := IF(@kb_col_exists = 0,
    'ALTER TABLE kb_documents ADD COLUMN kb_id CHAR(36) NULL AFTER user_id',
    'SELECT 1');
PREPARE kb_col_stmt FROM @kb_col_ddl;
EXECUTE kb_col_stmt;
DEALLOCATE PREPARE kb_col_stmt;

INSERT INTO knowledge_bases (id, user_id, name, is_default, created_at, updated_at)
SELECT UUID(), u.user_id, '默认知识库', TRUE, NOW(6), NOW(6)
FROM (
    SELECT user_id FROM kb_documents
    UNION
    SELECT user_id FROM sessions
) u
WHERE NOT EXISTS (
    SELECT 1 FROM knowledge_bases kb WHERE kb.user_id = u.user_id AND kb.is_default = TRUE
);

-- Existing vectors lack kb_id, so mark only the rows we just backfilled stale.
UPDATE kb_documents d
JOIN knowledge_bases kb ON kb.user_id = d.user_id AND kb.is_default = TRUE
SET d.kb_id = kb.id,
    d.status = 'stale',
    d.version = d.version + 1
WHERE d.kb_id IS NULL;

INSERT INTO session_knowledge_bases (session_id, kb_id, created_at)
SELECT s.id, kb.id, NOW(6)
FROM sessions s
JOIN knowledge_bases kb ON kb.user_id = s.user_id AND kb.is_default = TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM session_knowledge_bases skb WHERE skb.session_id = s.id
);

SET @kb_nullable := (
    SELECT IS_NULLABLE FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_documents' AND COLUMN_NAME = 'kb_id'
);
SET @kb_not_null_ddl := IF(@kb_nullable = 'YES',
    'ALTER TABLE kb_documents MODIFY COLUMN kb_id CHAR(36) NOT NULL',
    'SELECT 1');
PREPARE kb_not_null_stmt FROM @kb_not_null_ddl;
EXECUTE kb_not_null_stmt;
DEALLOCATE PREPARE kb_not_null_stmt;

SET @kb_index_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_documents' AND INDEX_NAME = 'idx_kb_documents_kb'
);
SET @kb_index_ddl := IF(@kb_index_exists = 0,
    'ALTER TABLE kb_documents ADD KEY idx_kb_documents_kb (user_id, kb_id, created_at)',
    'SELECT 1');
PREPARE kb_index_stmt FROM @kb_index_ddl;
EXECUTE kb_index_stmt;
DEALLOCATE PREPARE kb_index_stmt;

SET @kb_fk_exists := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_documents'
      AND CONSTRAINT_NAME = 'fk_kb_documents_kb'
);
SET @kb_fk_ddl := IF(@kb_fk_exists = 0,
    'ALTER TABLE kb_documents ADD CONSTRAINT fk_kb_documents_kb FOREIGN KEY (kb_id) REFERENCES knowledge_bases (id)',
    'SELECT 1');
PREPARE kb_fk_stmt FROM @kb_fk_ddl;
EXECUTE kb_fk_stmt;
DEALLOCATE PREPARE kb_fk_stmt;
