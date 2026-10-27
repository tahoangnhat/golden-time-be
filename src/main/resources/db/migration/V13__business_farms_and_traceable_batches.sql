ALTER TABLE shops
    ADD COLUMN business_email VARCHAR(255),
    ADD COLUMN business_phone VARCHAR(32),
    ADD COLUMN tax_code VARCHAR(64),
    ADD COLUMN representative_name VARCHAR(255),
    ADD COLUMN representative_phone VARCHAR(32),
    ADD COLUMN logo_url VARCHAR(1024);

ALTER TABLE business_applications
    ALTER COLUMN registration_number DROP NOT NULL,
    ALTER COLUMN license_file DROP NOT NULL,
    ALTER COLUMN license_original_name DROP NOT NULL,
    ALTER COLUMN license_content_type DROP NOT NULL,
    ADD COLUMN tax_code VARCHAR(64),
    ADD COLUMN representative_phone VARCHAR(32),
    ADD COLUMN logo_url VARCHAR(1024);

UPDATE business_applications
SET tax_code = NULLIF(registration_number, '')
WHERE tax_code IS NULL;

UPDATE shops s
SET business_email = u.email,
    business_phone = u.phone,
    representative_name = u.full_name,
    representative_phone = u.phone
FROM users u
WHERE u.shop_id = s.id;

UPDATE shops s
SET business_email = a.email,
    business_phone = a.phone,
    tax_code = a.tax_code,
    representative_name = a.contact_name,
    representative_phone = a.representative_phone,
    logo_url = a.logo_url
FROM business_applications a
WHERE a.shop_id = s.id;

CREATE TABLE farms (
    id                  BIGSERIAL PRIMARY KEY,
    shop_id             BIGINT NOT NULL REFERENCES shops (id) ON DELETE CASCADE,
    name                VARCHAR(255) NOT NULL,
    address             VARCHAR(512) NOT NULL,
    manager_name        VARCHAR(255) NOT NULL,
    phone               VARCHAR(32) NOT NULL,
    fruit_types         TEXT NOT NULL,
    area_hectares       NUMERIC(10, 2),
    certifications      TEXT,
    image_url           VARCHAR(1024),
    status              VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                            CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    rejection_reason    VARCHAR(1000),
    reviewed_by         BIGINT REFERENCES users (id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    reviewed_at         TIMESTAMPTZ
);

CREATE INDEX idx_farms_shop_status ON farms (shop_id, status, created_at DESC);
CREATE INDEX idx_farms_status_created ON farms (status, created_at DESC);

ALTER TABLE trace_batches
    ADD COLUMN product_id BIGINT REFERENCES shop_products (id),
    ADD COLUMN shop_id BIGINT REFERENCES shops (id),
    ADD COLUMN farm_id BIGINT REFERENCES farms (id),
    ADD COLUMN quantity_kg NUMERIC(12, 2),
    ADD COLUMN packaging_date DATE,
    ADD COLUMN quality_checked_at DATE,
    ADD COLUMN quality_result VARCHAR(64),
    ADD COLUMN quality_score INT CHECK (quality_score BETWEEN 0 AND 100),
    ADD COLUMN quality_note TEXT,
    ADD COLUMN transport_date DATE,
    ADD COLUMN transport_info TEXT,
    ADD COLUMN batch_status VARCHAR(24) NOT NULL DEFAULT 'IN_PROGRESS'
        CHECK (batch_status IN ('IN_PROGRESS', 'READY', 'IN_TRANSIT', 'DELIVERED', 'RECALLED'));

UPDATE trace_batches b
SET product_id = p.id,
    shop_id = p.shop_id
FROM shop_products p
WHERE p.batch_code = b.batch_code;

CREATE INDEX idx_trace_batches_shop_created ON trace_batches (shop_id, created_at DESC);
CREATE INDEX idx_trace_batches_farm ON trace_batches (farm_id, created_at DESC);
CREATE INDEX idx_trace_batches_product ON trace_batches (product_id, created_at DESC);
