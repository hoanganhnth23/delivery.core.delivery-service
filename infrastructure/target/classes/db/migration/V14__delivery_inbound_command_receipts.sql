CREATE TABLE delivery_inbound_receipts (
    event_id UUID PRIMARY KEY,
    command_type VARCHAR(64) NOT NULL,
    order_id BIGINT NOT NULL,
    delivery_id BIGINT NULL,
    payload_fingerprint VARCHAR(64) NOT NULL,
    received_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_delivery_inbound_receipts_order
    ON delivery_inbound_receipts (order_id, received_at);
