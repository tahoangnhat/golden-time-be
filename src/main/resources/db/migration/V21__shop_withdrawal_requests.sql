CREATE TABLE shop_withdrawal_requests (
    id                      BIGSERIAL PRIMARY KEY,
    shop_id                 BIGINT NOT NULL REFERENCES shops (id),
    amount                  BIGINT NOT NULL CHECK (amount > 0),
    status                  VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                                CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'PAID', 'CANCELLED')),
    bank_code               VARCHAR(32) NOT NULL,
    bank_name               VARCHAR(120) NOT NULL,
    account_number_encrypted TEXT NOT NULL,
    account_number_last4    VARCHAR(4) NOT NULL,
    account_name_encrypted  TEXT NOT NULL,
    requested_by            BIGINT REFERENCES users (id),
    requested_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    reviewed_by             BIGINT REFERENCES users (id),
    reviewed_at             TIMESTAMPTZ,
    review_note             VARCHAR(1000),
    paid_at                 TIMESTAMPTZ,
    transfer_reference      VARCHAR(255),
    transfer_note           VARCHAR(1000),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_shop_withdrawal_requests_shop_time
    ON shop_withdrawal_requests (shop_id, requested_at DESC, id DESC);
CREATE INDEX idx_shop_withdrawal_requests_status_time
    ON shop_withdrawal_requests (status, requested_at DESC, id DESC);

ALTER TABLE shop_wallet_transactions
    ADD COLUMN withdrawal_request_id BIGINT REFERENCES shop_withdrawal_requests (id);

CREATE UNIQUE INDEX uq_shop_wallet_withdrawal_payout
    ON shop_wallet_transactions (withdrawal_request_id)
    WHERE withdrawal_request_id IS NOT NULL AND entry_type = 'PAYOUT';
