ALTER TABLE product_reviews
    DROP CONSTRAINT uq_product_reviews_user_product;

CREATE UNIQUE INDEX uq_product_reviews_purchase_product
    ON product_reviews (order_id, shop_product_id)
    WHERE order_id IS NOT NULL;

UPDATE shop_products p
SET rating_avg = COALESCE((
        SELECT ROUND(AVG(pr.rating)::numeric, 2)
        FROM product_reviews pr
        WHERE pr.shop_product_id = p.id
    ), 0.00),
    review_count = (
        SELECT COUNT(*)
        FROM product_reviews pr
        WHERE pr.shop_product_id = p.id
    );

UPDATE shops s
SET rating_avg = COALESCE((
        SELECT ROUND(AVG(pr.rating)::numeric, 2)
        FROM product_reviews pr
        JOIN shop_products p ON p.id = pr.shop_product_id
        WHERE p.shop_id = s.id
    ), 0.00),
    review_count = (
        SELECT COUNT(*)
        FROM product_reviews pr
        JOIN shop_products p ON p.id = pr.shop_product_id
        WHERE p.shop_id = s.id
    );
