package com.delivery.delivery.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class DeliveryBatchItemTest {
    @Test
    void rejectsInvalidRouteOrder() {
        assertThrows(IllegalArgumentException.class, () -> new DeliveryBatchItem(1L, 2L, 3, 3));
        assertThrows(IllegalArgumentException.class, () -> new DeliveryBatchItem(null, 2L, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new DeliveryBatchItem(1L, null, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new DeliveryBatchItem(1L, 2L, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new DeliveryBatchItem(1L, 2L, 1, -1));
    }

    @Test
    void acceptsValidRoute() {
        DeliveryBatchItem item = new DeliveryBatchItem(1L, 2L, 0, 1);
        org.junit.jupiter.api.Assertions.assertEquals(1L, item.deliveryId());
    }
}
