package com.delivery.delivery.domain;

/** Persisted delivery lifecycle. Exception states remain additive to the MVP flow. */
public enum DeliveryStatus {
    PENDING, FINDING_SHIPPER, WAIT_SHIPPER_CONFIRM, SHIPPER_NOT_FOUND,
    ASSIGNED, PICKED_UP, DELIVERING, DELIVERED, RETURNING, RETURNED, CANCELLED
}
