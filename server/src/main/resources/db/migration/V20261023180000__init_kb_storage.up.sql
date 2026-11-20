-- Up-only migration (ADR-0009). KB metadata (MySQL), originals (S3, ADR-0013)
-- and vectors (Qdrant, ADR-0005) share the document id as their only join key.
CREATE TABLE IF NOT EXISTS kb_documents (
    id CHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    source_type ENUM('upload', 'url') NOT NULL,
    source_url VARCHAR(2048) NULL,
    original_key VARCHAR(512) NOT NULL,
    original_sha256 CHAR(64) COLLATE utf8mb4_0900_as_cs NOT NULL,
    mime VARCHAR(255) NULL,
    size_bytes BIGINT NOT NULL,
    status ENUM('queued', 'processing', 'ready', 'failed', 'deleting', 'stale') NOT NULL DEFAULT 'queued',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NULL,
    lease_until DATETIME(6) NULL,
    enqueued_at DATETIME(6) NULL,
    chunk_count INT NOT NULL DEFAULT 0,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(1024) NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_kb_documents_user (user_id, created_at, id),
    KEY idx_kb_documents_dispatch (status, enqueued_at, next_attempt_at),
    CONSTRAINT fk_kb_documents_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS kb_passages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    document_id CHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    seq INT NOT NULL,
    page INT NULL,
    content MEDIUMTEXT NOT NULL,
    char_count INT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_passages_document_seq (document_id, seq),
    KEY idx_kb_passages_user_document (user_id, document_id),
    CONSTRAINT fk_kb_passages_document FOREIGN KEY (document_id)
        REFERENCES kb_documents (id) ON DELETE CASCADE,
    CONSTRAINT fk_kb_passages_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS kb_user_settings (
    user_id BIGINT NOT NULL,
    embedding_base_url VARCHAR(1000) NULL,
    embedding_api_key_cipher TEXT NULL,
    embedding_model VARCHAR(200) NULL,
    embedding_dim INT NULL,
    embedding_path VARCHAR(255) NULL,
    rerank_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    rerank_base_url VARCHAR(1000) NULL,
    rerank_api_key_cipher TEXT NULL,
    rerank_model VARCHAR(200) NULL,
    rerank_path VARCHAR(255) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id),
    CONSTRAINT fk_kb_user_settings_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
