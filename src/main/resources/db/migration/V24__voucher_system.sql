-- ====================================================================
-- Golden Time Database Migration: V24__voucher_system.sql
-- Mô tả: Khởi tạo bảng định nghĩa voucher của cửa hàng (Shop Voucher),
--         ví voucher của khách hàng và bổ sung các trường voucher vào bảng đơn hàng (orders)
-- ====================================================================

-- 1. Bảng định nghĩa voucher (Business / Cửa hàng tạo và quản lý)
CREATE TABLE vouchers (
    id                      BIGSERIAL PRIMARY KEY,
    code                    VARCHAR(50) NOT NULL UNIQUE,
    name                    VARCHAR(255) NOT NULL,
    description             TEXT,
    discount_type           VARCHAR(16) NOT NULL CHECK (discount_type IN ('PERCENT', 'FIXED')),
    discount_value          NUMERIC(12, 2) NOT NULL CHECK (discount_value > 0),
    max_discount_amount     BIGINT CHECK (max_discount_amount IS NULL OR max_discount_amount > 0),
    min_order_value         BIGINT NOT NULL DEFAULT 0 CHECK (min_order_value >= 0),
    shop_id                 BIGINT NOT NULL REFERENCES shops (id) ON DELETE RESTRICT,
    total_quantity          INT NOT NULL CHECK (total_quantity >= 0),
    claimed_count           INT NOT NULL DEFAULT 0,
    used_count              INT NOT NULL DEFAULT 0,
    per_user_claim_limit    INT NOT NULL DEFAULT 1 CHECK (per_user_claim_limit > 0),
    per_user_use_limit      INT NOT NULL DEFAULT 1 CHECK (per_user_use_limit > 0),
    claim_start_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    claim_end_at            TIMESTAMPTZ,
    valid_from              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    valid_until             TIMESTAMPTZ NOT NULL,
    enabled                 BOOLEAN NOT NULL DEFAULT TRUE,
    created_by              BIGINT REFERENCES users (id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_voucher_claimed_limit CHECK (claimed_count >= 0 AND claimed_count <= total_quantity),
    CONSTRAINT chk_voucher_used_limit CHECK (used_count >= 0 AND used_count <= claimed_count),
    CONSTRAINT chk_voucher_valid_time CHECK (valid_until >= valid_from),
    CONSTRAINT chk_voucher_claim_window CHECK (claim_end_at IS NULL OR claim_end_at >= claim_start_at),
    CONSTRAINT chk_voucher_percent_value CHECK (discount_type <> 'PERCENT' OR (discount_value > 0 AND discount_value <= 100))
);

CREATE INDEX idx_vouchers_shop ON vouchers (shop_id);
CREATE INDEX idx_vouchers_enabled ON vouchers (enabled, valid_from, valid_until);

-- 2. Bảng ví voucher của Buyer (mỗi dòng là 1 vé trong ví)
CREATE TABLE buyer_vouchers (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    voucher_id          BIGINT NOT NULL REFERENCES vouchers (id) ON DELETE RESTRICT,
    grant_type          VARCHAR(16) NOT NULL CHECK (grant_type IN ('CLAIM', 'AUTO_GRANT')),
    status              VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE'
                            CHECK (status IN ('AVAILABLE', 'RESERVED', 'USED', 'EXPIRED', 'REVOKED')),
    reserved_order_id   BIGINT REFERENCES orders (id) ON DELETE SET NULL,
    reserved_at         TIMESTAMPTZ,
    used_at             TIMESTAMPTZ,
    claimed_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_buyer_vouchers_user_status ON buyer_vouchers (user_id, status);
CREATE INDEX idx_buyer_vouchers_voucher ON buyer_vouchers (voucher_id);
CREATE INDEX idx_buyer_vouchers_order ON buyer_vouchers (reserved_order_id) WHERE reserved_order_id IS NOT NULL;

-- 3. Bổ sung các cột liên kết trên bảng đơn hàng (orders)
ALTER TABLE orders
    ADD COLUMN discount_amount BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN voucher_id BIGINT REFERENCES vouchers (id) ON DELETE SET NULL,
    ADD COLUMN buyer_voucher_id BIGINT REFERENCES buyer_vouchers (id) ON DELETE SET NULL;

CREATE INDEX idx_orders_voucher ON orders (voucher_id) WHERE voucher_id IS NOT NULL;
CREATE INDEX idx_orders_buyer_voucher ON orders (buyer_voucher_id) WHERE buyer_voucher_id IS NOT NULL;
