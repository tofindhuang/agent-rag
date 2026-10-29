-- Up-only migration (ADR-0027): OIDC identities and passwordless accounts.
-- Safe to re-run manually: CREATE TABLE IF NOT EXISTS and the ALTER below
-- only change metadata, never rewrite existing password hashes.
ALTER TABLE users MODIFY COLUMN password_hash VARCHAR(100) NULL;

CREATE TABLE IF NOT EXISTS user_identities (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    issuer          VARCHAR(255) NOT NULL COLLATE utf8mb4_0900_as_cs,
    subject         VARCHAR(255) NOT NULL COLLATE utf8mb4_0900_as_cs,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_login_at   DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_identities_issuer_subject (issuer, subject),
    UNIQUE KEY uk_user_identities_user_issuer (user_id, issuer),
    KEY idx_user_identities_user (user_id),
    CONSTRAINT fk_user_identities_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
