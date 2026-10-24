-- Up-only migration (ADR-0009). Long-term profile facts (FR-5, ADR-0020).
CREATE TABLE IF NOT EXISTS profile_facts (
    id CHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    category VARCHAR(32) NOT NULL,
    fact_key VARCHAR(128) NOT NULL,
    fact_value VARCHAR(512) NOT NULL,
    fact_text VARCHAR(1024) NOT NULL,
    status ENUM('active', 'superseded', 'deleted') NOT NULL,
    supersedes_id CHAR(36) NULL,
    source_session_id CHAR(36) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    active_key VARCHAR(300) GENERATED ALWAYS AS (
        CASE
            WHEN status = 'active' THEN CONCAT(user_id, ':', category, ':', fact_key)
        END
    ) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_profile_active (active_key),
    KEY idx_profile_user_status (user_id, status, updated_at),
    CONSTRAINT fk_profile_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_profile_supersedes FOREIGN KEY (supersedes_id) REFERENCES profile_facts (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS profile_settings (
    user_id BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id),
    CONSTRAINT fk_profile_settings_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
