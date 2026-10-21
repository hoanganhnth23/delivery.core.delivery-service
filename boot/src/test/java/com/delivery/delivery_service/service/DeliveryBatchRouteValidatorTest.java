package com.delivery.delivery_service.service;

import com.delivery.delivery_service.dto.event.ShipperFoundEvent;
import com.delivery.delivery_service.exception.InvalidStatusException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeliveryBatchRouteValidatorTest {

    @Test
    void acceptsGlobalTwoTimesNStopsWithStrictPickupPrecedence() {
        List<ShipperFoundEvent.BatchItem> items = List.of(
                item(1L, 101L, 0, 2),
                item(2L, 102L, 1, 3));

        assertDoesNotThrow(() -> DeliveryBatchRouteValidator.validate(items));
    }

    @Test
    void rejectsDuplicatedOrEqualStops() {
        assertThrows(InvalidStatusException.class, () -> DeliveryBatchRouteValidator.validate(List.of(
                item(1L, 101L, 0, 0))));
        assertThrows(InvalidStatusException.class, () -> DeliveryBatchRouteValidator.validate(List.of(
                item(1L, 101L, 0, 2),
                item(1L, 102L, 1, 3))));
    }

    @Test
    void rejectsASequenceGapEvenWhenEveryItemIsUnique() {
        assertThrows(InvalidStatusException.class, () -> DeliveryBatchRouteValidator.validate(List.of(
                item(1L, 101L, 0, 2),
                item(2L, 102L, 1, 4))));
    }

    private ShipperFoundEvent.BatchItem item(Long deliveryId, Long orderId, int pickup, int dropoff) {
        return new ShipperFoundEvent.BatchItem(deliveryId, orderId, pickup, dropoff,
                null, UUID.randomUUID());
    }
}
