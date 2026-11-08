CREATE TABLE platform_settings (
    setting_key   VARCHAR(100) PRIMARY KEY,
    setting_value TEXT NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO platform_settings (setting_key, setting_value)
VALUES ('premium_price_vnd', '0');

CREATE TABLE premium_payments (
    order_code       BIGINT PRIMARY KEY,
    user_id          BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    amount_vnd       BIGINT NOT NULL CHECK (amount_vnd > 0),
    status           VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                         CHECK (status IN ('PENDING', 'PAID', 'CANCELLED', 'EXPIRED')),
    payment_link_id  VARCHAR(128) UNIQUE,
    checkout_url     TEXT,
    qr_code          TEXT,
    reference        VARCHAR(128),
    expires_at       TIMESTAMPTZ NOT NULL,
    paid_at          TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_premium_payments_user ON premium_payments (user_id, created_at DESC);

CREATE TABLE premium_memberships (
    user_id                   BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    expires_at                TIMESTAMPTZ NOT NULL,
    last_payment_order_code   BIGINT REFERENCES premium_payments (order_code),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE premium_health_profiles (
    user_id             BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    encrypted_profile  TEXT NOT NULL,
    consent_at           TIMESTAMPTZ NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
