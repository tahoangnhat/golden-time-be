ALTER TABLE shops
    ADD COLUMN google_place_id VARCHAR(255);

ALTER TABLE business_applications
    ADD COLUMN latitude DOUBLE PRECISION,
    ADD COLUMN longitude DOUBLE PRECISION,
    ADD COLUMN google_place_id VARCHAR(255);
