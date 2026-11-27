ALTER TABLE orders
    ALTER COLUMN user_id DROP NOT NULL;

ALTER TABLE orders
    DROP CONSTRAINT IF EXISTS orders_user_id_fkey;

ALTER TABLE orders
    ADD CONSTRAINT fk_orders_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL;
