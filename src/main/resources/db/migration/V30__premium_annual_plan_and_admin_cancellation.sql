INSERT INTO platform_settings (setting_key, setting_value)
SELECT 'premium_annual_price_vnd',
       CASE WHEN setting_value ~ '^[0-9]{1,9}$'
            THEN LEAST(setting_value::BIGINT * 12, 999999999)::TEXT
            ELSE '0'
       END
FROM platform_settings
WHERE setting_key = 'premium_price_vnd'
ON CONFLICT (setting_key) DO NOTHING;

ALTER TABLE premium_payments
    ADD COLUMN plan_code VARCHAR(16) NOT NULL DEFAULT 'MONTHLY',
    ADD COLUMN duration_days INTEGER NOT NULL DEFAULT 30,
    ADD CONSTRAINT premium_payments_plan_code_check CHECK (plan_code IN ('MONTHLY', 'YEARLY')),
    ADD CONSTRAINT premium_payments_duration_check CHECK (
        (plan_code = 'MONTHLY' AND duration_days = 30)
        OR (plan_code = 'YEARLY' AND duration_days = 365)
    );

ALTER TABLE premium_memberships
    ADD COLUMN cancelled_at TIMESTAMPTZ,
    ADD COLUMN cancelled_by BIGINT REFERENCES users (id) ON DELETE SET NULL;
