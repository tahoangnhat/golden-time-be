ALTER TABLE shop_products
    ALTER COLUMN fruit_id DROP NOT NULL,
    ADD COLUMN product_category VARCHAR(32);

UPDATE shop_products p
SET product_category = CASE
    WHEN p.is_prepared THEN 'PREPARED'
    WHEN p.is_seasonal THEN 'SEASONAL'
    WHEN LOWER(COALESCE(f.category, '')) LIKE '%nhập khẩu%'
      OR LOWER(COALESCE(f.category, '')) LIKE '%import%' THEN 'IMPORTED'
    ELSE 'LOCAL'
END
FROM fruits f
WHERE f.id = p.fruit_id;

ALTER TABLE shop_products
    ALTER COLUMN product_category SET NOT NULL,
    ADD CONSTRAINT chk_shop_products_product_category
        CHECK (product_category IN ('LOCAL', 'IMPORTED', 'SEASONAL', 'PREPARED'));

CREATE INDEX idx_shop_products_category_status
    ON shop_products (product_category, status);
