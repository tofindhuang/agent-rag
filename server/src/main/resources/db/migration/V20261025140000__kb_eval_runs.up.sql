-- Up-only migration (ADR-0009, ADR-0022). One evaluation run is an immutable
-- summary plus per-label detail; item rows cascade with their run. Metrics are
-- DECIMAL so a replayed report never drifts on float formatting.
CREATE TABLE IF NOT EXISTS kb_eval_runs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    kb_id CHAR(36) NOT NULL,
    complete BOOLEAN NOT NULL,
    label_count INT NOT NULL,
    executed_count INT NOT NULL,
    rotten_count INT NOT NULL,
    skipped_count INT NOT NULL,
    embedding_model VARCHAR(255) NOT NULL,
    embedding_dim INT NOT NULL,
    rerank_model VARCHAR(255) NULL,
    duration_ms INT NOT NULL,
    recall5 DECIMAL(6,4) NULL,
    mrr5 DECIMAL(6,4) NULL,
    pool_recall10 DECIMAL(6,4) NULL,
    baseline_recall5 DECIMAL(6,4) NULL,
    baseline_mrr5 DECIMAL(6,4) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_kb_eval_runs_kb (user_id, kb_id, created_at),
    CONSTRAINT fk_kb_eval_runs_kb FOREIGN KEY (kb_id)
        REFERENCES knowledge_bases (id) ON DELETE CASCADE,
    CONSTRAINT fk_kb_eval_runs_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS kb_eval_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    label_id BIGINT NOT NULL,
    status ENUM('hit', 'miss', 'rotten', 'skipped') NOT NULL,
    rank_final INT NULL,
    rank_vector INT NULL,
    score_final DOUBLE NULL,
    PRIMARY KEY (id),
    KEY idx_kb_eval_items_run (run_id),
    CONSTRAINT fk_kb_eval_items_run FOREIGN KEY (run_id)
        REFERENCES kb_eval_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_kb_eval_items_label FOREIGN KEY (label_id)
        REFERENCES kb_eval_labels (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
