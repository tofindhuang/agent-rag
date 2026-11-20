-- Cold archive ledger for pi session files (ADR-0025). Up-only and safe to
-- re-run manually: a second execution is a no-op.
CREATE TABLE IF NOT EXISTS session_archives (
    session_id     VARCHAR(64)  NOT NULL,
    user_id        BIGINT       NOT NULL,
    object_prefix  VARCHAR(255) NOT NULL,
    jsonl_sha256   CHAR(64)     NULL,
    meta_sha256    CHAR(64)     NULL,
    size_bytes     BIGINT       NOT NULL DEFAULT 0,
    status         VARCHAR(16)  NOT NULL,
    attempts       INT          NOT NULL DEFAULT 0,
    last_error     VARCHAR(512) NULL,
    archived_at    DATETIME(6)  NULL,
    updated_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (session_id),
    KEY idx_session_archives_user (user_id),
    KEY idx_session_archives_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
