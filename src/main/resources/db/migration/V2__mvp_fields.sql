ALTER TABLE users
    ADD COLUMN IF NOT EXISTS age INT,
    ADD COLUMN IF NOT EXISTS weight_kg NUMERIC(5, 2),
    ADD COLUMN IF NOT EXISTS health_goal VARCHAR(64);

ALTER TABLE ai_scans
    ADD COLUMN IF NOT EXISTS shop_id BIGINT REFERENCES shops (id),
    ADD COLUMN IF NOT EXISTS confidence NUMERIC(5, 4),
    ADD COLUMN IF NOT EXISTS analysis_mode VARCHAR(24) NOT NULL DEFAULT 'DEMO',
    ADD COLUMN IF NOT EXISTS batch_code VARCHAR(64) REFERENCES trace_batches (batch_code);

ALTER TABLE trace_batches
    ADD COLUMN IF NOT EXISTS harvest_date DATE,
    ADD COLUMN IF NOT EXISTS intake_date DATE,
    ADD COLUMN IF NOT EXISTS certificates TEXT,
    ADD COLUMN IF NOT EXISTS storage_temperature VARCHAR(64),
    ADD COLUMN IF NOT EXISTS storage_humidity VARCHAR(64),
    ADD COLUMN IF NOT EXISTS public_note TEXT;

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS payment_method VARCHAR(32) NOT NULL DEFAULT 'COD',
    ADD COLUMN IF NOT EXISTS delivery_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS delivery_phone VARCHAR(32),
    ADD COLUMN IF NOT EXISTS delivery_address VARCHAR(512),
    ADD COLUMN IF NOT EXISTS subtotal_amount BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS shipping_fee BIGINT NOT NULL DEFAULT 15000;

ALTER TABLE articles
    ADD COLUMN IF NOT EXISTS cover_url VARCHAR(1024);

CREATE TABLE IF NOT EXISTS market_price_sources (
    id                  BIGSERIAL PRIMARY KEY,
    fruit_id            BIGINT NOT NULL REFERENCES fruits (id) ON DELETE CASCADE,
    retailer_id         BIGINT NOT NULL REFERENCES market_retailers (id) ON DELETE CASCADE,
    product_name        VARCHAR(255) NOT NULL,
    source_url          VARCHAR(1024) NOT NULL UNIQUE,
    package_grams       NUMERIC(10, 2),
    price_is_per_kg     BOOLEAN NOT NULL DEFAULT FALSE,
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    last_attempt_at     TIMESTAMPTZ,
    last_success_at     TIMESTAMPTZ,
    last_error           TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_market_price_sources_enabled
    ON market_price_sources (enabled, retailer_id, fruit_id);

CREATE INDEX IF NOT EXISTS idx_market_prices_fetched
    ON market_prices (fruit_id, fetched_at DESC);

CREATE INDEX IF NOT EXISTS idx_trace_events_batch_order
    ON trace_events (batch_code, step_order);

CREATE UNIQUE INDEX IF NOT EXISTS uq_reviews_order_proof
    ON reviews (order_id) WHERE order_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_reviews_scan_proof
    ON reviews (scan_id) WHERE scan_id IS NOT NULL;

