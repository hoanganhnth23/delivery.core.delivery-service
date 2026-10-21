CREATE TABLE IF NOT EXISTS delivery_proofs (
    proof_id UUID PRIMARY KEY,
    delivery_id BIGINT NOT NULL,
    shipper_id BIGINT NOT NULL,
    storage_provider VARCHAR(80) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    declared_size_bytes BIGINT NOT NULL,
    verified_size_bytes BIGINT NULL,
    object_checksum VARCHAR(128) NULL,
    status VARCHAR(32) NOT NULL,
    upload_expires_at TIMESTAMP NOT NULL,
    confirmed_at TIMESTAMP NULL,
    retention_expires_at TIMESTAMP NULL,
    purged_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_delivery_proofs_object_key UNIQUE (object_key),
    CONSTRAINT fk_delivery_proofs_delivery FOREIGN KEY (delivery_id) REFERENCES deliveries(id),
    CONSTRAINT ck_delivery_proofs_status
        CHECK (status IN ('UPLOAD_PENDING', 'CONFIRMED', 'EXPIRED', 'PURGED')),
    CONSTRAINT ck_delivery_proofs_size
        CHECK (declared_size_bytes > 0 AND declared_size_bytes <= 10485760)
);

CREATE INDEX IF NOT EXISTS idx_delivery_proofs_delivery_status
    ON delivery_proofs (delivery_id, status, confirmed_at);
CREATE INDEX IF NOT EXISTS idx_delivery_proofs_retention
    ON delivery_proofs (status, retention_expires_at);

CREATE TABLE IF NOT EXISTS delivery_exceptions (
    exception_id UUID PRIMARY KEY,
    delivery_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    shipper_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    customer_principal_id BIGINT NULL,
    restaurant_id BIGINT NOT NULL,
    reason TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    reported_at TIMESTAMP NOT NULL,
    retry_deadline_at TIMESTAMP NOT NULL,
    retry_used_at TIMESTAMP NULL,
    returning_at TIMESTAMP NULL,
    returned_at TIMESTAMP NULL,
    returned_by_principal_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_delivery_exceptions_delivery UNIQUE (delivery_id),
    CONSTRAINT fk_delivery_exceptions_delivery FOREIGN KEY (delivery_id) REFERENCES deliveries(id),
    CONSTRAINT ck_delivery_exceptions_status
        CHECK (status IN ('RETRY_AVAILABLE', 'RETRY_USED', 'RETURNING', 'RETURNED', 'RESOLVED'))
);

CREATE INDEX IF NOT EXISTS idx_delivery_exceptions_retry
    ON delivery_exceptions (status, retry_deadline_at);
CREATE INDEX IF NOT EXISTS idx_delivery_exceptions_order
    ON delivery_exceptions (order_id, created_at);

-- V18 created strict item-status checks. Expand them forward-only so an active
-- batch can retain an item while it is returned without pretending it delivered.
ALTER TABLE delivery_batch_items DROP CONSTRAINT IF EXISTS ck_delivery_batch_item_status;
ALTER TABLE delivery_batch_items ADD CONSTRAINT ck_delivery_batch_item_status
    CHECK (item_status IN ('OFFERED', 'ACCEPTED', 'PICKED_UP', 'DELIVERING', 'DELIVERED',
                           'RETURNING', 'RETURNED', 'CANCELLED', 'RETIRED'));
