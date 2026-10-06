INSERT INTO fruits (name, slug, emoji, category, search_keywords) VALUES
    ('Táo', 'tao', '🍎', 'Trái cây nhập khẩu', 'táo táo fuji gala apple'),
    ('Cam sành', 'cam-sanh', '🍊', 'Trái cây nội địa', 'cam sành cam orange'),
    ('Xoài cát Hòa Lộc', 'xoai-cat-hoa-loc', '🥭', 'Trái cây nội địa', 'xoài xoài cát mango'),
    ('Chuối', 'chuoi', '🍌', 'Trái cây nội địa', 'chuối chuối già banana'),
    ('Nho', 'nho', '🍇', 'Trái cây nhập khẩu', 'nho nho mẫu đơn grape'),
    ('Dâu tây', 'dau-tay', '🍓', 'Trái cây nội địa', 'dâu dâu tây strawberry')
ON CONFLICT (slug) DO UPDATE SET
    name = EXCLUDED.name,
    emoji = EXCLUDED.emoji,
    category = EXCLUDED.category,
    search_keywords = EXCLUDED.search_keywords;

INSERT INTO fruit_nutrition (fruit_id, calories_per_100g, vitamin_c_mg, fiber_g, sugar_g, potassium_mg, water_percent, health_benefits, serving_note, personalized_tip)
SELECT id, 52, 4.6, 2.4, 10.4, 107, 86,
       'Hỗ trợ tiêu hóa; phù hợp ăn nhẹ; cung cấp chất xơ.',
       'Khẩu phần tham khảo: 1 quả khoảng 150g.',
       'Có thể ăn 1–2 quả mỗi ngày tùy mục tiêu sức khỏe.'
FROM fruits WHERE slug = 'tao'
ON CONFLICT (fruit_id) DO UPDATE SET
    calories_per_100g = EXCLUDED.calories_per_100g,
    vitamin_c_mg = EXCLUDED.vitamin_c_mg,
    fiber_g = EXCLUDED.fiber_g,
    sugar_g = EXCLUDED.sugar_g,
    potassium_mg = EXCLUDED.potassium_mg,
    water_percent = EXCLUDED.water_percent,
    health_benefits = EXCLUDED.health_benefits,
    serving_note = EXCLUDED.serving_note,
    personalized_tip = EXCLUDED.personalized_tip;

INSERT INTO fruit_nutrition (fruit_id, calories_per_100g, vitamin_c_mg, fiber_g, sugar_g, potassium_mg, water_percent, health_benefits, serving_note, personalized_tip)
SELECT id, 47, 53.2, 2.4, 9.4, 181, 87,
       'Nguồn vitamin C; bổ sung nước và chất xơ.',
       'Khẩu phần tham khảo: 1 quả vừa.',
       'Dùng nguyên múi để giữ lại chất xơ.'
FROM fruits WHERE slug = 'cam-sanh'
ON CONFLICT (fruit_id) DO NOTHING;

INSERT INTO fruit_nutrition (fruit_id, calories_per_100g, vitamin_c_mg, fiber_g, sugar_g, potassium_mg, water_percent, health_benefits, serving_note, personalized_tip)
SELECT id, 60, 36.4, 1.6, 13.7, 168, 83,
       'Bổ sung vitamin C; phù hợp dùng như món ăn nhẹ.',
       'Khẩu phần tham khảo: 1 chén xoài cắt.',
       'Ưu tiên khẩu phần vừa phải khi theo dõi lượng đường.'
FROM fruits WHERE slug = 'xoai-cat-hoa-loc'
ON CONFLICT (fruit_id) DO NOTHING;

INSERT INTO fruit_nutrition (fruit_id, calories_per_100g, vitamin_c_mg, fiber_g, sugar_g, potassium_mg, water_percent, health_benefits, serving_note, personalized_tip)
SELECT id, 89, 8.7, 2.6, 12.2, 358, 75,
       'Cung cấp năng lượng và kali.',
       'Khẩu phần tham khảo: 1 quả vừa.',
       'Có thể dùng trước hoặc sau vận động.'
FROM fruits WHERE slug = 'chuoi'
ON CONFLICT (fruit_id) DO NOTHING;

INSERT INTO market_retailers (code, name, website) VALUES
    ('BACHHOAXANH', 'Bách Hóa Xanh', 'https://www.bachhoaxanh.com'),
    ('WINMART', 'WinMart', 'https://winmart.vn')
ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, website = EXCLUDED.website;

INSERT INTO shops (name, address, latitude, longitude, rating_avg, review_count, status, description, popular_fruit)
SELECT 'Trái Cây Sạch Sài Gòn', 'Quận 1, TP. Hồ Chí Minh', 10.7769, 106.7009, 0, 0, 'ACTIVE', 'Cửa hàng trái cây tươi trong ngày.', 'Xoài cát Hòa Lộc'
WHERE NOT EXISTS (SELECT 1 FROM shops WHERE name = 'Trái Cây Sạch Sài Gòn');

INSERT INTO shops (name, address, latitude, longitude, rating_avg, review_count, status, description, popular_fruit)
SELECT 'Vườn Xoài Cát Lái', 'TP. Thủ Đức, TP. Hồ Chí Minh', 10.8015, 106.7870, 0, 0, 'ACTIVE', 'Trái cây theo mùa, nguồn cung địa phương.', 'Xoài cát'
WHERE NOT EXISTS (SELECT 1 FROM shops WHERE name = 'Vườn Xoài Cát Lái');

INSERT INTO shops (name, address, latitude, longitude, rating_avg, review_count, status, description, popular_fruit)
SELECT 'Fresh Fruit Q.1', 'Quận 1, TP. Hồ Chí Minh', 10.7830, 106.6950, 0, 0, 'ACTIVE', 'Cửa hàng trái cây nhập khẩu và nội địa.', 'Táo'
WHERE NOT EXISTS (SELECT 1 FROM shops WHERE name = 'Fresh Fruit Q.1');

INSERT INTO trace_batches (batch_code, fruit_id, product_name, origin, supplier, certified, harvest_date, intake_date, certificates, storage_temperature, storage_humidity, public_note)
SELECT 'TF-MC-2026-0618', f.id, 'Táo Fuji Mộc Châu', 'Mộc Châu, Sơn La', 'HTX Trái Cây Mộc Châu', TRUE,
       DATE '2026-06-15', DATE '2026-06-18', 'VietGAP, GlobalG.A.P', '4–6°C', '90%', 'Thông tin lô hàng mẫu dùng cho prototype.'
FROM fruits f WHERE f.slug = 'tao'
ON CONFLICT (batch_code) DO UPDATE SET
    fruit_id = EXCLUDED.fruit_id,
    product_name = EXCLUDED.product_name,
    origin = EXCLUDED.origin,
    supplier = EXCLUDED.supplier,
    certified = EXCLUDED.certified,
    harvest_date = EXCLUDED.harvest_date,
    intake_date = EXCLUDED.intake_date,
    certificates = EXCLUDED.certificates,
    storage_temperature = EXCLUDED.storage_temperature,
    storage_humidity = EXCLUDED.storage_humidity,
    public_note = EXCLUDED.public_note;

INSERT INTO trace_events (batch_code, step_order, title, event_date, location, icon_key, completed)
SELECT 'TF-MC-2026-0618', v.step_order, v.title, v.event_date, v.location, v.icon_key, v.completed
FROM (VALUES
    (1, 'Thu hoạch', '15/06/2026', 'Mộc Châu', 'harvest', TRUE),
    (2, 'Kiểm định chất lượng', '16/06/2026', 'Trung tâm kiểm định', 'quality', TRUE),
    (3, 'Vận chuyển', '17/06/2026', 'Xe lạnh 4°C', 'transport', TRUE),
    (4, 'Nhập cửa hàng', '18/06/2026', 'Quận 1, TP. Hồ Chí Minh', 'store', TRUE),
    (5, 'Đến tay người dùng', '—', NULL, 'customer', FALSE)
) AS v(step_order, title, event_date, location, icon_key, completed)
WHERE NOT EXISTS (SELECT 1 FROM trace_events WHERE batch_code = 'TF-MC-2026-0618' AND step_order = v.step_order);

INSERT INTO shop_products (shop_id, fruit_id, display_name, price_per_kg, stock_kg, ai_score, status, batch_code)
SELECT s.id, f.id, 'Táo Fuji', 49000, 32, 92, 'ON_SALE', 'TF-MC-2026-0618'
FROM shops s CROSS JOIN fruits f
WHERE s.name = 'Trái Cây Sạch Sài Gòn' AND f.slug = 'tao'
AND NOT EXISTS (SELECT 1 FROM shop_products p WHERE p.shop_id = s.id AND p.display_name = 'Táo Fuji');

INSERT INTO shop_products (shop_id, fruit_id, display_name, price_per_kg, stock_kg, ai_score, status)
SELECT s.id, f.id, v.display_name, v.price_per_kg, v.stock_kg, v.ai_score, 'ON_SALE'
FROM (VALUES
    ('Trái Cây Sạch Sài Gòn', 'cam-sanh', 'Cam sành', 35000::BIGINT, 20::NUMERIC, 88),
    ('Trái Cây Sạch Sài Gòn', 'xoai-cat-hoa-loc', 'Xoài cát Hòa Lộc', 65000::BIGINT, 15::NUMERIC, 90),
    ('Vườn Xoài Cát Lái', 'xoai-cat-hoa-loc', 'Xoài cát Hòa Lộc', 62000::BIGINT, 25::NUMERIC, 90),
    ('Vườn Xoài Cát Lái', 'chuoi', 'Chuối già', 28000::BIGINT, 40::NUMERIC, 85),
    ('Fresh Fruit Q.1', 'tao', 'Táo Fuji', 52000::BIGINT, 18::NUMERIC, 92),
    ('Fresh Fruit Q.1', 'nho', 'Nho mẫu đơn', 199000::BIGINT, 8::NUMERIC, 94),
    ('Fresh Fruit Q.1', 'dau-tay', 'Dâu Đà Lạt', 120000::BIGINT, 10::NUMERIC, 91)
) AS v(shop_name, fruit_slug, display_name, price_per_kg, stock_kg, ai_score)
JOIN shops s ON s.name = v.shop_name
JOIN fruits f ON f.slug = v.fruit_slug
WHERE NOT EXISTS (SELECT 1 FROM shop_products p WHERE p.shop_id = s.id AND p.display_name = v.display_name);

INSERT INTO market_price_sources (fruit_id, retailer_id, product_name, source_url, package_grams, price_is_per_kg)
SELECT f.id, r.id, v.product_name, v.source_url, v.package_grams, v.price_is_per_kg
FROM (VALUES
    ('tao', 'BACHHOAXANH', 'Táo Gala mini nhập khẩu túi 400g', 'https://www.bachhoaxanh.com/trai-cay-tuoi-ngon/tao-gala-mini-nhap-khau-new-zealand-tui-400g/', 400::NUMERIC, FALSE),
    ('tao', 'WINMART', 'Táo xanh', 'https://winmart.vn/products/tao-xanh--s10054817', NULL::NUMERIC, TRUE),
    ('cam-sanh', 'WINMART', 'Cam sành loại 1', 'https://winmart.vn/products/cam-sanh-loai-1--s10242468', NULL::NUMERIC, TRUE)
) AS v(fruit_slug, retailer_code, product_name, source_url, package_grams, price_is_per_kg)
JOIN fruits f ON f.slug = v.fruit_slug
JOIN market_retailers r ON r.code = v.retailer_code
ON CONFLICT (source_url) DO NOTHING;

INSERT INTO articles (slug, title, category, emoji, gradient, description, body, author, published_at, reading_time, published)
VALUES
('cach-nhan-biet-trai-cay-tuoi', 'Cách nhận biết trái cây còn tươi trước khi mua', 'Chọn trái cây', '🍎', 'from-rose-200 to-orange-100', 'Mẹo đơn giản giúp bạn chọn được trái cây tươi ngon, hạn chế mua phải hàng cũ, hàng hỏng.', 'Quan sát màu sắc, độ cứng, mùi hương và bề mặt vỏ. Tránh những quả rỉ nước, mốc hoặc có mùi lên men. Kết quả AI chỉ mang tính tham khảo.', 'Đội ngũ Golden Time', DATE '2026-03-12', '6 phút', TRUE),
('an-tao-moi-ngay', 'Ăn táo mỗi ngày có lợi ích gì?', 'Dinh dưỡng', '🍏', 'from-lime-200 to-emerald-100', 'Phân tích thành phần dinh dưỡng và lợi ích sức khỏe khi duy trì thói quen ăn 1 quả táo mỗi ngày.', 'Táo có chất xơ và carbohydrate tự nhiên. Khẩu phần phù hợp tùy nhu cầu dinh dưỡng cá nhân; không dùng nội dung này thay cho tư vấn y tế.', 'BS. Nguyễn Minh Anh', DATE '2026-03-05', '5 phút', TRUE),
('bao-quan-chuoi', 'Cách bảo quản chuối để lâu không bị hỏng', 'Bảo quản', '🍌', 'from-yellow-200 to-amber-100', 'Hướng dẫn bảo quản chuối để giảm thâm và giữ chất lượng.', 'Để chuối ở nơi thoáng mát, tách quả dập khỏi quả còn nguyên và kiểm tra thường xuyên. Nhiệt độ bảo quản phù hợp còn tùy độ chín.', 'Đội ngũ Golden Time', DATE '2026-02-28', '4 phút', TRUE),
('trai-cay-theo-mua', 'Nên mua trái cây theo mùa như thế nào?', 'Theo mùa', '🥭', 'from-yellow-200 to-orange-100', 'Mua đúng mùa có thể giúp người dùng có thêm lựa chọn về độ tươi và chi phí.', 'Tìm hiểu mùa vụ tại địa phương và kiểm tra giá, nguồn gốc ở thời điểm mua. Mùa vụ có thể khác nhau theo vùng trồng.', 'Trần Hoài Phương', DATE '2026-02-20', '7 phút', TRUE),
('so-sanh-gia-trai-cay', 'Cách so sánh giá trái cây để tránh mua đắt', 'Mua sắm thông minh', '🍇', 'from-purple-200 to-fuchsia-100', 'Bí quyết so sánh giá theo khối lượng và điểm bán.', 'Khi so sánh, hãy quy đổi cùng đơn vị khối lượng và xem thời điểm cập nhật. Giá trực tuyến có thể khác theo khu vực và chương trình khuyến mãi.', 'Đội ngũ Golden Time', DATE '2026-02-14', '6 phút', TRUE),
('trai-cay-cho-nguoi-tap-gym', 'Trái cây nào phù hợp với người tập gym?', 'Sức khỏe', '🍓', 'from-rose-200 to-pink-100', 'Gợi ý cách đưa trái cây vào bữa ăn quanh thời gian tập luyện.', 'Nhu cầu năng lượng và khẩu phần tùy lịch tập, sức khỏe và chế độ ăn. Tham khảo chuyên gia khi cần chế độ dinh dưỡng điều trị.', 'HLV. Lê Quốc Bảo', DATE '2026-02-05', '8 phút', TRUE)
ON CONFLICT (slug) DO UPDATE SET
    title = EXCLUDED.title,
    category = EXCLUDED.category,
    emoji = EXCLUDED.emoji,
    gradient = EXCLUDED.gradient,
    description = EXCLUDED.description,
    body = EXCLUDED.body,
    author = EXCLUDED.author,
    published_at = EXCLUDED.published_at,
    reading_time = EXCLUDED.reading_time,
    published = EXCLUDED.published,
    updated_at = NOW();
