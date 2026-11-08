ALTER TABLE orders
    ADD COLUMN shop_rating INTEGER CHECK (shop_rating BETWEEN 1 AND 5);

CREATE INDEX idx_orders_shop_rating
    ON orders (shop_id) WHERE shop_rating IS NOT NULL;

-- Preserve ratings previously submitted with an order. Scan-only shop reviews
-- are intentionally excluded from the order-based shop rating.
UPDATE orders o
SET shop_rating = r.rating
FROM reviews r
WHERE r.order_id = o.id;

UPDATE shops s
SET rating_avg = COALESCE((
        SELECT ROUND(AVG(o.shop_rating)::numeric, 2)
        FROM orders o
        WHERE o.shop_id = s.id AND o.shop_rating IS NOT NULL
    ), 0),
    review_count = (
        SELECT COUNT(*)
        FROM orders o
        WHERE o.shop_id = s.id AND o.shop_rating IS NOT NULL
    );
