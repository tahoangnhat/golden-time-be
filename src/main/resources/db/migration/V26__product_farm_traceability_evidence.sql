ALTER TABLE shop_products
    ADD COLUMN trace_farm_id BIGINT REFERENCES farms (id),
    ADD COLUMN trace_order_date DATE,
    ADD COLUMN trace_transport_date DATE,
    ADD COLUMN trace_received_date DATE,
    ADD COLUMN trace_evidence_image_url VARCHAR(1024);

CREATE INDEX idx_shop_products_trace_farm ON shop_products (trace_farm_id);
