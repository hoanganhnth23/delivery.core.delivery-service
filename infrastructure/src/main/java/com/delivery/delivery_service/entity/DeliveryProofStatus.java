package com.delivery.delivery_service.entity;

/** Lifecycle of a private proof-of-delivery object. */
public enum DeliveryProofStatus {
    UPLOAD_PENDING,
    CONFIRMED,
    EXPIRED,
    PURGED
}
