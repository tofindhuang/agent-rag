-- Up-only migration. Snapshot of the model chosen for a session (ADR-0010).
CREATE TABLE IF NOT EXISTS session_models (
    session_id CHAR(36) NOT NULL,
    provider_model_id BIGINT NULL,
    model_label VARCHAR(300) NOT NULL,
    model_source ENUM('system', 'user') NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (session_id),
    CONSTRAINT fk_session_models_session FOREIGN KEY (session_id)
        REFERENCES sessions (id) ON DELETE CASCADE,
    CONSTRAINT fk_session_models_model FOREIGN KEY (provider_model_id)
        REFERENCES provider_models (id) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
