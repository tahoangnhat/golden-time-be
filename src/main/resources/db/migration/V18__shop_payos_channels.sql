ALTER TABLE shops
    ADD COLUMN payos_client_id_encrypted TEXT,
    ADD COLUMN payos_api_key_encrypted TEXT,
    ADD COLUMN payos_checksum_key_encrypted TEXT,
    ADD COLUMN payos_webhook_verified_at TIMESTAMPTZ;
