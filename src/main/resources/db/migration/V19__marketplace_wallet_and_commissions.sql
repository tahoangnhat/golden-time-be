ALTER TABLE shops
    ADD COLUMN approved_at TIMESTAMPTZ,
    ADD COLUMN commission_tier VARCHAR(24) NOT NULL DEFAULT 'STANDARD'
        CHECK (commission_tier IN ('NEW_SHOP', 'STANDARD', 'LARGE_PARTNER', 'CUSTOM')),
    ADD COLUMN custom_commission_rate NUMERIC(5, 2)
        CHECK (custom_commission_rate IS NULL OR (custom_commission_rate >= 0 AND custom_commission_rate <= 100));

UPDATE shops SET approved_at = created_at WHERE approved_at IS NULL;

CREATE TABLE shop_wallet_transactions (
    id                  BIGSERIAL PRIMARY KEY,
    shop_id             BIGINT NOT NULL REFERENCES shops (id),
    order_id            BIGINT REFERENCES orders (id),
    entry_type          VARCHAR(24) NOT NULL
                            CHECK (entry_type IN ('ORDER_SALE', 'COMMISSION_FEE', 'PAYOUT')),
    amount              BIGINT NOT NULL CHECK (amount <> 0),
    gross_amount        BIGINT,
    commission_rate     NUMERIC(5, 2),
    commission_amount   BIGINT,
    external_reference  VARCHAR(255),
    description         VARCHAR(1000),
    created_by          BIGINT REFERENCES users (id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_shop_wallet_order_entry UNIQUE (order_id, entry_type)
);

CREATE INDEX idx_shop_wallet_transactions_shop_created
    ON shop_wallet_transactions (shop_id, created_at DESC, id DESC);
