CREATE TABLE refresh_tokens (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash      CHAR(64) NOT NULL UNIQUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked_at      TIMESTAMPTZ
);

CREATE INDEX idx_refresh_tokens_user_active
    ON refresh_tokens (user_id, expires_at)
    WHERE revoked_at IS NULL;
