package com.delivery.delivery.domain;

/** Framework-free batch item used by matching and persistence adapters. */
public record DeliveryBatchItem(Long deliveryId, Long orderId, int pickupSequence, int dropoffSequence) {
    public DeliveryBatchItem {
        if (deliveryId == null || orderId == null) throw new IllegalArgumentException("deliveryId and orderId are required");
        if (pickupSequence < 0 || dropoffSequence < 0 || pickupSequence >= dropoffSequence) {
            throw new IllegalArgumentException("pickup must precede dropoff");
        }
    }

}
