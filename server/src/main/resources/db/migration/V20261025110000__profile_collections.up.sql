-- Up-only migration (ADR-0009). Scenario memory collections (FR-5 extension).
CREATE TABLE IF NOT EXISTS profile_collections (
    id CHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    name VARCHAR(64) NOT NULL,
    system_key ENUM('general', 'inbox') NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_profile_collection_system (user_id, system_key),
    UNIQUE KEY uk_profile_collection_name (user_id, name),
    CONSTRAINT fk_profile_collection_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE profile_facts
    ADD COLUMN fact_title VARCHAR(80) NULL AFTER fact_value,
    ADD COLUMN collection_id CHAR(36) NULL AFTER fact_title;

INSERT INTO profile_collections (id, user_id, name, system_key, created_at, updated_at)
SELECT UUID(), users_with_facts.user_id, '通用', 'general', NOW(6), NOW(6)
FROM (SELECT DISTINCT user_id FROM profile_facts) AS users_with_facts
WHERE NOT EXISTS (
    SELECT 1 FROM profile_collections c
    WHERE c.user_id = users_with_facts.user_id AND c.system_key = 'general'
);

INSERT INTO profile_collections (id, user_id, name, system_key, created_at, updated_at)
SELECT UUID(), users_with_facts.user_id, '收件箱', 'inbox', NOW(6), NOW(6)
FROM (SELECT DISTINCT user_id FROM profile_facts) AS users_with_facts
WHERE NOT EXISTS (
    SELECT 1 FROM profile_collections c
    WHERE c.user_id = users_with_facts.user_id AND c.system_key = 'inbox'
);

UPDATE profile_facts f
JOIN profile_collections c
  ON c.user_id = f.user_id AND c.system_key = 'general'
SET f.collection_id = c.id,
    f.fact_title = LEFT(f.fact_text, 80)
WHERE f.collection_id IS NULL;

ALTER TABLE profile_facts
    MODIFY COLUMN fact_title VARCHAR(80) NOT NULL,
    MODIFY COLUMN collection_id CHAR(36) NOT NULL,
    ADD KEY idx_profile_collection (user_id, collection_id, status, updated_at),
    ADD CONSTRAINT fk_profile_fact_collection
        FOREIGN KEY (collection_id) REFERENCES profile_collections (id);

CREATE TABLE IF NOT EXISTS session_profile_collections (
    session_id CHAR(36) NOT NULL,
    collection_id CHAR(36) NOT NULL,
    PRIMARY KEY (session_id, collection_id),
    CONSTRAINT fk_session_profile_collection_session
        FOREIGN KEY (session_id) REFERENCES sessions (id) ON DELETE CASCADE,
    CONSTRAINT fk_session_profile_collection_collection
        FOREIGN KEY (collection_id) REFERENCES profile_collections (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
