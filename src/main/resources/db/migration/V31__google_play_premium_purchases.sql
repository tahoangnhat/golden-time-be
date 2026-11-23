CREATE TABLE google_play_premium_purchases (
    purchase_token TEXT PRIMARY KEY,
    user_id        BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    product_id     VARCHAR(120) NOT NULL,
    plan_code      VARCHAR(16) NOT NULL CHECK (plan_code IN ('MONTHLY', 'YEARLY')),
    duration_days  INTEGER NOT NULL CHECK (duration_days IN (30, 365)),
    consumed_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_google_play_premium_user ON google_play_premium_purchases (user_id, created_at DESC);
