CREATE TABLE business_applications (
    id                      BIGSERIAL PRIMARY KEY,
    business_name           VARCHAR(255) NOT NULL,
    registration_number     VARCHAR(64) NOT NULL,
    contact_name            VARCHAR(255) NOT NULL,
    email                   VARCHAR(255) NOT NULL,
    phone                   VARCHAR(32) NOT NULL,
    address                 VARCHAR(512) NOT NULL,
    password_hash           VARCHAR(255),
    license_file            VARCHAR(255) NOT NULL,
    license_original_name   VARCHAR(255) NOT NULL,
    license_content_type    VARCHAR(128) NOT NULL,
    status                  VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                                CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    rejection_reason        VARCHAR(1000),
    shop_id                 BIGINT REFERENCES shops (id),
    reviewed_by             BIGINT REFERENCES users (id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    reviewed_at              TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_business_applications_pending_email
    ON business_applications (LOWER(email))
    WHERE status = 'PENDING';

CREATE INDEX idx_business_applications_status_created
    ON business_applications (status, created_at DESC);
