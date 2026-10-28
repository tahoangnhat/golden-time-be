ALTER TABLE shop_products
    ADD COLUMN product_origin VARCHAR(512),
    ADD COLUMN supplier_name VARCHAR(255),
    ADD COLUMN lot_code VARCHAR(128),
    ADD COLUMN traceability_proof TEXT,
    ADD COLUMN approval_status VARCHAR(24) NOT NULL DEFAULT 'APPROVED',
    ADD COLUMN review_note TEXT,
    ADD COLUMN reviewed_by BIGINT REFERENCES users (id),
    ADD COLUMN reviewed_at TIMESTAMPTZ,
    ADD CONSTRAINT chk_shop_products_approval_status
        CHECK (approval_status IN ('PENDING_REVIEW', 'APPROVED', 'NEEDS_CORRECTION'));

CREATE INDEX idx_shop_products_approval_queue
    ON shop_products (approval_status, created_at DESC);
