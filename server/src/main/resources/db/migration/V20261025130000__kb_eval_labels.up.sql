-- Up-only migration (ADR-0009, ADR-0022). Evaluation labels pin one question
-- to one passage of one knowledge base. The text fingerprint turns a
-- re-chunked passage into an explicit "rotten" state instead of silently
-- measuring the wrong target.
CREATE TABLE IF NOT EXISTS kb_eval_labels (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    kb_id CHAR(36) NOT NULL,
    query VARCHAR(512) NOT NULL,
    document_id CHAR(36) NOT NULL,
    seq INT NOT NULL,
    passage_sha256 CHAR(64) COLLATE utf8mb4_0900_as_cs NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_eval_labels_query (kb_id, query),
    KEY idx_kb_eval_labels_kb (user_id, kb_id, created_at),
    CONSTRAINT fk_kb_eval_labels_kb FOREIGN KEY (kb_id)
        REFERENCES knowledge_bases (id) ON DELETE CASCADE,
    CONSTRAINT fk_kb_eval_labels_document FOREIGN KEY (document_id)
        REFERENCES kb_documents (id) ON DELETE CASCADE,
    CONSTRAINT fk_kb_eval_labels_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
