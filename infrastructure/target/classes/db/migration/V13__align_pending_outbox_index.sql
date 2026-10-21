-- Match retry-first SKIP LOCKED polling and retain deterministic ordering for
-- events sharing the same retry/creation timestamp.
DROP INDEX IF EXISTS idx_delivery_outbox_pending;
CREATE INDEX idx_delivery_outbox_pending
    ON outbox_events (status, next_attempt_at, created_at, id);
