ALTER TABLE shop_products
    ADD COLUMN rating_avg NUMERIC(3, 2) NOT NULL DEFAULT 0.00,
    ADD COLUMN review_count INT NOT NULL DEFAULT 0;

CREATE TABLE product_reviews (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    shop_product_id BIGINT NOT NULL REFERENCES shop_products (id) ON DELETE CASCADE,
    order_id        BIGINT REFERENCES orders (id) ON DELETE SET NULL,
    rating          INT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment         TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_product_reviews_user_product UNIQUE (user_id, shop_product_id)
);

CREATE INDEX idx_product_reviews_product
    ON product_reviews (shop_product_id, created_at DESC, id DESC);

CREATE INDEX idx_product_reviews_user
    ON product_reviews (user_id);

CREATE INDEX idx_product_reviews_order
    ON product_reviews (order_id);
