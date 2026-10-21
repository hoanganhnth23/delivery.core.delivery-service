ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS customer_principal_id BIGINT;
ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS restaurant_owner_principal_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_delivery_customer_principal ON deliveries (customer_principal_id, id);
