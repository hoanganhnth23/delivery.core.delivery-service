ALTER TABLE outbox_events
    DROP CONSTRAINT IF EXISTS outbox_events_status_check;
ALTER TABLE outbox_events
    DROP CONSTRAINT IF EXISTS ck_delivery_outbox_status;

ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS lease_token UUID NULL;
ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMP NULL;

ALTER TABLE outbox_events
    ADD CONSTRAINT ck_delivery_outbox_status
    CHECK (status IN ('PENDING', 'IN_FLIGHT', 'SENT', 'DEAD'));

CREATE INDEX IF NOT EXISTS idx_delivery_outbox_claimable
    ON outbox_events (status, next_attempt_at, lease_until, created_at, id);
