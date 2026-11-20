-- Up-only migration (ADR-0010). User-supplied model providers and enabled models.
CREATE TABLE IF NOT EXISTS model_providers (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    api ENUM('openai-completions', 'anthropic-messages') NOT NULL,
    base_url VARCHAR(1000) NOT NULL,
    api_key_cipher TEXT NOT NULL,
    api_key_hint VARCHAR(8) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_model_providers_user (user_id),
    CONSTRAINT fk_model_providers_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS provider_models (
    id BIGINT NOT NULL AUTO_INCREMENT,
    provider_id BIGINT NOT NULL,
    model_id VARCHAR(200) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    context_window INT NULL,
    supports_reasoning BOOLEAN NOT NULL DEFAULT FALSE,
    supports_vision BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_provider_models_provider_model (provider_id, model_id),
    CONSTRAINT fk_provider_models_provider FOREIGN KEY (provider_id)
        REFERENCES model_providers (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS user_model_defaults (
    user_id BIGINT NOT NULL,
    model_id BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id),
    CONSTRAINT fk_user_model_defaults_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_user_model_defaults_model FOREIGN KEY (model_id)
        REFERENCES provider_models (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
