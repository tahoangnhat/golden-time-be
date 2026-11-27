CREATE TABLE account_deletion_requests (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  CHAR(64) NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_account_deletion_requests_user_created
    ON account_deletion_requests (user_id, created_at DESC);
