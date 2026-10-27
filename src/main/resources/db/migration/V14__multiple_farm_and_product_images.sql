CREATE TABLE farm_images (
    id            BIGSERIAL PRIMARY KEY,
    farm_id       BIGINT NOT NULL REFERENCES farms (id) ON DELETE CASCADE,
    image_url     VARCHAR(1024) NOT NULL,
    display_order INT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_farm_images_farm_order ON farm_images (farm_id, display_order, id);

INSERT INTO farm_images (farm_id, image_url, display_order)
SELECT id, image_url, 0 FROM farms WHERE image_url IS NOT NULL AND BTRIM(image_url) <> '';

CREATE TABLE shop_product_images (
    id              BIGSERIAL PRIMARY KEY,
    shop_product_id BIGINT NOT NULL REFERENCES shop_products (id) ON DELETE CASCADE,
    image_url       VARCHAR(1024) NOT NULL,
    display_order   INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_shop_product_images_product_order ON shop_product_images (shop_product_id, display_order, id);

INSERT INTO shop_product_images (shop_product_id, image_url, display_order)
SELECT id, image_url, 0 FROM shop_products WHERE image_url IS NOT NULL AND BTRIM(image_url) <> '';
