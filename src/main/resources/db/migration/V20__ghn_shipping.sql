ALTER TABLE shops
    ADD COLUMN pickup_province_name VARCHAR(255),
    ADD COLUMN pickup_ward_name VARCHAR(255);

ALTER TABLE orders
    ADD COLUMN delivery_province_name VARCHAR(255),
    ADD COLUMN delivery_ward_name VARCHAR(255);

CREATE TABLE order_shipments (
    id                      BIGSERIAL PRIMARY KEY,
    order_id                BIGINT NOT NULL UNIQUE REFERENCES orders (id) ON DELETE CASCADE,
    provider                VARCHAR(16) NOT NULL DEFAULT 'GHN',
    carrier_order_code      VARCHAR(64) NOT NULL UNIQUE,
    client_order_code       VARCHAR(50) NOT NULL UNIQUE,
    carrier_status          VARCHAR(64),
    carrier_fee             BIGINT NOT NULL DEFAULT 0,
    quoted_fee              BIGINT NOT NULL DEFAULT 0,
    cod_amount              BIGINT NOT NULL DEFAULT 0,
    expected_delivery_at    TIMESTAMPTZ,
    last_event_at           TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE shipment_events (
    id                      BIGSERIAL PRIMARY KEY,
    shipment_id             BIGINT NOT NULL REFERENCES order_shipments (id) ON DELETE CASCADE,
    carrier_order_code      VARCHAR(64) NOT NULL,
    event_type              VARCHAR(64) NOT NULL,
    carrier_status          VARCHAR(64),
    event_time              TIMESTAMPTZ NOT NULL,
    description             VARCHAR(512),
    reason                  VARCHAR(512),
    total_fee               BIGINT,
    cod_transfer_at         TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_shipment_event UNIQUE (carrier_order_code, event_type, event_time)
);

CREATE INDEX idx_shipment_events_shipment_time
    ON shipment_events (shipment_id, event_time DESC);
