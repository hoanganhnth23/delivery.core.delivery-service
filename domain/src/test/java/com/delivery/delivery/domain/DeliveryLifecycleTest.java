package com.delivery.delivery.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryLifecycleTest {
    @Test
    void modelsHappyPathAndTerminalStates() {
        assertTrue(DeliveryLifecycle.canTransition(DeliveryStatus.PENDING, DeliveryStatus.FINDING_SHIPPER));
        assertTrue(DeliveryLifecycle.canTransition(DeliveryStatus.ASSIGNED, DeliveryStatus.PICKED_UP));
        assertTrue(DeliveryLifecycle.canTransition(DeliveryStatus.DELIVERING, DeliveryStatus.DELIVERED));
        assertFalse(DeliveryLifecycle.canTransition(DeliveryStatus.DELIVERED, DeliveryStatus.ASSIGNED));
    }

    @Test
    void rejectsMissingTransitionValues() {
        assertFalse(DeliveryLifecycle.canTransition(null, DeliveryStatus.PENDING));
        assertFalse(DeliveryLifecycle.canTransition(DeliveryStatus.PENDING, null));
        assertFalse(DeliveryLifecycle.canTransition(DeliveryStatus.PENDING, DeliveryStatus.PENDING));
        for (DeliveryStatus status : DeliveryStatus.values()) {
            DeliveryLifecycle.canTransition(status, DeliveryStatus.CANCELLED);
        }
    }
}
