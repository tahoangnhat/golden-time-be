-- Remove identifiable prototype content while preserving order history and user-created records.
DELETE FROM articles
WHERE slug IN (
    'cach-nhan-biet-trai-cay-tuoi', 'an-tao-moi-ngay', 'bao-quan-chuoi',
    'trai-cay-theo-mua', 'so-sanh-gia-trai-cay', 'trai-cay-cho-nguoi-tap-gym'
);

DELETE FROM fruit_nutrition
WHERE health_benefits IN (
    'Hỗ trợ tiêu hóa; phù hợp ăn nhẹ; cung cấp chất xơ.',
    'Nguồn vitamin C; bổ sung nước và chất xơ.',
    'Bổ sung vitamin C; phù hợp dùng như món ăn nhẹ.',
    'Cung cấp năng lượng và kali.'
);

-- Clear the seeded trace batch. Historical product rows, if already referenced by an order,
-- keep their order-item links, and historical scans keep their results but no longer point
-- at the sample trace record.
UPDATE shop_products SET batch_code = NULL WHERE batch_code = 'TF-MC-2026-0618';
UPDATE ai_scans SET batch_code = NULL WHERE batch_code = 'TF-MC-2026-0618';
DELETE FROM trace_batches WHERE batch_code = 'TF-MC-2026-0618';

-- Remove the products inserted for the three prototype shops. Preserve any product referenced
-- by an order for historical display, but hide it from sale.
UPDATE shop_products p SET status = 'HIDDEN', stock_kg = 0
FROM shops s
WHERE p.shop_id = s.id
  AND s.name IN ('Trái Cây Sạch Sài Gòn', 'Vườn Xoài Cát Lái', 'Fresh Fruit Q.1')
  AND EXISTS (SELECT 1 FROM order_items oi WHERE oi.shop_product_id = p.id);

DELETE FROM shop_products p
USING shops s
WHERE p.shop_id = s.id
  AND s.name IN ('Trái Cây Sạch Sài Gòn', 'Vườn Xoài Cát Lái', 'Fresh Fruit Q.1')
  AND NOT EXISTS (SELECT 1 FROM order_items oi WHERE oi.shop_product_id = p.id);

DELETE FROM shops s
WHERE s.name IN ('Trái Cây Sạch Sài Gòn', 'Vườn Xoài Cát Lái', 'Fresh Fruit Q.1')
  AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.shop_id = s.id)
  AND NOT EXISTS (SELECT 1 FROM shop_products p WHERE p.shop_id = s.id);

UPDATE shops SET status = 'SUSPENDED'
WHERE name IN ('Trái Cây Sạch Sài Gòn', 'Vườn Xoài Cát Lái', 'Fresh Fruit Q.1');

-- Disable built-in demo credentials on databases where the earlier prototype seeder ran.
UPDATE users SET enabled = FALSE
WHERE email IN ('user@goldentime.vn', 'admin@goldentime.vn');

-- Older instances could have saved fabricated fallback AI results. Remove those and
-- reviews whose only proof was one of those fabricated scans.
DELETE FROM reviews
WHERE scan_id IN (SELECT id FROM ai_scans WHERE analysis_mode = 'DEMO');
DELETE FROM ai_scans WHERE analysis_mode = 'DEMO';

ALTER TABLE ai_scans ALTER COLUMN analysis_mode SET DEFAULT 'PROVIDER';
