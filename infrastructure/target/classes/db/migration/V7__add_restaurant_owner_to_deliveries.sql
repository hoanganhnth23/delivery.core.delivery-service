ALTER TABLE deliveries
    ADD COLUMN IF NOT EXISTS restaurant_owner_id BIGINT NULL;

CREATE INDEX IF NOT EXISTS idx_deliveries_restaurant_owner
    ON deliveries (restaurant_owner_id, created_at);
