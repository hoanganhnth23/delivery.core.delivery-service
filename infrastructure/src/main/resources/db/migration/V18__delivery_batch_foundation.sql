CREATE TABLE IF NOT EXISTS delivery_batches (
    batch_id UUID PRIMARY KEY,
    shipper_id BIGINT NULL,
    status VARCHAR(24) NOT NULL,
    offer_expires_at TIMESTAMP NULL,
    route_version INTEGER NOT NULL DEFAULT 0,
    total_cod_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    accepted_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    CONSTRAINT ck_delivery_batch_status
        CHECK (status IN ('OFFERED', 'ACCEPTED', 'PICKED_UP', 'DELIVERING', 'COMPLETED', 'CANCELLED', 'RETIRED'))
);

CREATE TABLE IF NOT EXISTS delivery_batch_items (
    batch_id UUID NOT NULL,
    delivery_id BIGINT NOT NULL,
    pickup_sequence INTEGER NOT NULL,
    dropoff_sequence INTEGER NOT NULL,
    item_status VARCHAR(24) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (batch_id, delivery_id),
    CONSTRAINT fk_delivery_batch_item_batch
        FOREIGN KEY (batch_id) REFERENCES delivery_batches(batch_id),
    CONSTRAINT ck_delivery_batch_item_status
        CHECK (item_status IN ('OFFERED', 'ACCEPTED', 'PICKED_UP', 'DELIVERING', 'DELIVERED', 'CANCELLED', 'RETIRED'))
);

CREATE INDEX IF NOT EXISTS idx_delivery_batch_items_delivery
    ON delivery_batch_items (delivery_id, item_status);
CREATE INDEX IF NOT EXISTS idx_delivery_batches_shipper
    ON delivery_batches (shipper_id, status, updated_at);
ALTER TABLE deliveries
    ADD COLUMN IF NOT EXISTS batch_id UUID NULL;
ALTER TABLE deliveries
    ADD COLUMN IF NOT EXISTS batch_sequence INTEGER NULL;

CREATE INDEX IF NOT EXISTS idx_deliveries_batch
    ON deliveries (batch_id, batch_sequence);
