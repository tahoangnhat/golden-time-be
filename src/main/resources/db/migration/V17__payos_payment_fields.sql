ALTER TABLE orders
    ADD COLUMN payment_status VARCHAR(24) NOT NULL DEFAULT 'UNPAID'
        CHECK (payment_status IN ('UNPAID', 'PENDING', 'PAID', 'CANCELLED', 'FAILED')),
    ADD COLUMN payment_expires_at TIMESTAMPTZ,
    ADD COLUMN payos_payment_link_id VARCHAR(128),
    ADD COLUMN payos_checkout_url VARCHAR(1024),
    ADD COLUMN payos_qr_code TEXT,
    ADD COLUMN payos_reference VARCHAR(255),
    ADD COLUMN paid_at TIMESTAMPTZ;

CREATE UNIQUE INDEX uq_orders_payos_payment_link
    ON orders (payos_payment_link_id)
    WHERE payos_payment_link_id IS NOT NULL;

CREATE INDEX idx_orders_pending_payment_expiry
    ON orders (payment_expires_at)
    WHERE payment_status = 'PENDING';
