ALTER TABLE deliveries
    ADD COLUMN IF NOT EXISTS offered_shipper_id BIGINT;
ALTER TABLE deliveries
    ADD COLUMN IF NOT EXISTS offer_expires_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_deliveries_offered_shipper
    ON deliveries (offered_shipper_id, offer_expires_at);
