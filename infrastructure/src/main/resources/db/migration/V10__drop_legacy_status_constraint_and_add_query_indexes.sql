-- Schema changes belong to Flyway, not a best-effort @PostConstruct mutation.
ALTER TABLE deliveries
    DROP CONSTRAINT IF EXISTS deliveries_status_check;

-- Repository query support. The one-active-delivery partial unique index remains
-- owned by V5 and the offered-shipper expiry index remains owned by V4.
CREATE INDEX IF NOT EXISTS idx_deliveries_shipper_created
    ON deliveries (shipper_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_deliveries_status_created
    ON deliveries (status, created_at DESC);
