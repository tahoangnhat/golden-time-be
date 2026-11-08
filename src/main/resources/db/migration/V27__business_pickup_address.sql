ALTER TABLE business_applications
    ADD COLUMN pickup_address VARCHAR(512),
    ADD COLUMN pickup_province_name VARCHAR(255),
    ADD COLUMN pickup_ward_name VARCHAR(255);

ALTER TABLE shops
    ADD COLUMN pickup_address VARCHAR(512);

UPDATE shops
SET pickup_address = address
WHERE pickup_address IS NULL;
