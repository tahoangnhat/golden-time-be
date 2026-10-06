CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    email           VARCHAR(255) NOT NULL UNIQUE,
    phone           VARCHAR(32),
    password_hash   VARCHAR(255) NOT NULL,
    full_name       VARCHAR(255) NOT NULL,
    role            VARCHAR(32) NOT NULL DEFAULT 'USER',
    shop_id         BIGINT,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE shops (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    address         VARCHAR(512),
    latitude        DOUBLE PRECISION NOT NULL,
    longitude       DOUBLE PRECISION NOT NULL,
    rating_avg      NUMERIC(3, 2) NOT NULL DEFAULT 0,
    review_count    INT NOT NULL DEFAULT 0,
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    description     TEXT,
    popular_fruit   VARCHAR(255),
    owner_user_id   BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE fruits (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    slug            VARCHAR(255) NOT NULL UNIQUE,
    emoji           VARCHAR(16) NOT NULL DEFAULT '🍎',
    category        VARCHAR(128),
    search_keywords TEXT
);

CREATE TABLE fruit_nutrition (
    fruit_id            BIGINT PRIMARY KEY REFERENCES fruits (id) ON DELETE CASCADE,
    calories_per_100g   NUMERIC(8, 2),
    vitamin_c_mg        NUMERIC(8, 2),
    fiber_g             NUMERIC(8, 2),
    sugar_g             NUMERIC(8, 2),
    potassium_mg        NUMERIC(8, 2),
    water_percent       NUMERIC(5, 2),
    health_benefits     TEXT,
    serving_note        VARCHAR(512),
    personalized_tip    TEXT
);

CREATE TABLE trace_batches (
    batch_code      VARCHAR(64) PRIMARY KEY,
    fruit_id        BIGINT REFERENCES fruits (id),
    product_name    VARCHAR(255) NOT NULL,
    origin          VARCHAR(255),
    supplier        VARCHAR(255),
    certified       BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE trace_events (
    id              BIGSERIAL PRIMARY KEY,
    batch_code      VARCHAR(64) NOT NULL REFERENCES trace_batches (batch_code) ON DELETE CASCADE,
    step_order      INT NOT NULL,
    title           VARCHAR(255) NOT NULL,
    event_date      VARCHAR(128),
    location        VARCHAR(255),
    icon_key        VARCHAR(64),
    completed       BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE shop_products (
    id              BIGSERIAL PRIMARY KEY,
    shop_id         BIGINT NOT NULL REFERENCES shops (id) ON DELETE CASCADE,
    fruit_id        BIGINT NOT NULL REFERENCES fruits (id),
    display_name    VARCHAR(255) NOT NULL,
    price_per_kg    BIGINT NOT NULL,
    stock_kg        NUMERIC(10, 2) NOT NULL DEFAULT 0,
    ai_score        INT,
    status          VARCHAR(32) NOT NULL DEFAULT 'ON_SALE',
    batch_code      VARCHAR(64) REFERENCES trace_batches (batch_code),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE users
    ADD CONSTRAINT fk_users_shop FOREIGN KEY (shop_id) REFERENCES shops (id);

ALTER TABLE shops
    ADD CONSTRAINT fk_shops_owner FOREIGN KEY (owner_user_id) REFERENCES users (id);

CREATE TABLE ai_scans (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users (id),
    image_path      VARCHAR(512),
    fruit_id        BIGINT REFERENCES fruits (id),
    detected_name   VARCHAR(255),
    quality_score   INT,
    freshness_score INT,
    ripeness_label  VARCHAR(64),
    sweetness_label VARCHAR(64),
    use_within      VARCHAR(64),
    quality_warning TEXT,
    suggestion      TEXT,
    raw_response    JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE market_retailers (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(32) NOT NULL UNIQUE,
    name            VARCHAR(255) NOT NULL,
    website         VARCHAR(512)
);

CREATE TABLE market_prices (
    id              BIGSERIAL PRIMARY KEY,
    fruit_id        BIGINT NOT NULL REFERENCES fruits (id) ON DELETE CASCADE,
    retailer_id     BIGINT NOT NULL REFERENCES market_retailers (id) ON DELETE CASCADE,
    price_per_kg    BIGINT NOT NULL,
    source_url      VARCHAR(1024),
    fetched_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (fruit_id, retailer_id)
);

CREATE TABLE orders (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users (id),
    shop_id         BIGINT NOT NULL REFERENCES shops (id),
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    total_amount    BIGINT NOT NULL,
    note            TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE order_items (
    id              BIGSERIAL PRIMARY KEY,
    order_id        BIGINT NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    shop_product_id BIGINT NOT NULL REFERENCES shop_products (id),
    quantity_kg     NUMERIC(10, 2) NOT NULL,
    unit_price      BIGINT NOT NULL,
    line_total      BIGINT NOT NULL
);

CREATE TABLE reviews (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users (id),
    shop_id         BIGINT NOT NULL REFERENCES shops (id) ON DELETE CASCADE,
    order_id        BIGINT REFERENCES orders (id),
    scan_id         BIGINT REFERENCES ai_scans (id),
    rating          INT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment         TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (order_id IS NOT NULL OR scan_id IS NOT NULL)
);

CREATE TABLE articles (
    id              BIGSERIAL PRIMARY KEY,
    slug            VARCHAR(255) NOT NULL UNIQUE,
    title           VARCHAR(512) NOT NULL,
    category        VARCHAR(128) NOT NULL,
    emoji           VARCHAR(16) DEFAULT '📄',
    gradient        VARCHAR(128),
    description     TEXT,
    body            TEXT NOT NULL,
    author          VARCHAR(255),
    published_at    DATE,
    reading_time    VARCHAR(32),
    published       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE app_updates (
    id              BIGSERIAL PRIMARY KEY,
    version         VARCHAR(32) NOT NULL,
    release_date    DATE NOT NULL,
    title           VARCHAR(512) NOT NULL,
    update_type     VARCHAR(32) NOT NULL,
    changes         TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_shops_geo ON shops (latitude, longitude);
CREATE INDEX idx_shop_products_shop ON shop_products (shop_id);
CREATE INDEX idx_ai_scans_user ON ai_scans (user_id, created_at DESC);
CREATE INDEX idx_orders_user ON orders (user_id);
CREATE INDEX idx_reviews_shop ON reviews (shop_id);
