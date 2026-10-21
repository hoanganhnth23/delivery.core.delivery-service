package com.delivery.delivery_service.entity;

/** Post-pickup exception state, kept separate from the legacy status event. */
public enum DeliveryExceptionStatus {
    RETRY_AVAILABLE,
    RETRY_USED,
    RETURNING,
    RETURNED,
    RESOLVED
}
