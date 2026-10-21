package com.delivery.delivery.application.api;

import com.delivery.delivery.domain.DeliveryStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/** Persistence boundary. Implementations may use JPA, but this contract does not. */
public interface DeliveryStoragePort {
    Optional<DeliverySnapshot> findById(Long deliveryId);
    DeliverySnapshot save(DeliverySnapshot delivery);

    record DeliverySnapshot(Long deliveryId, Long orderId, Long userId, Long restaurantId,
                            Long shipperId, DeliveryStatus status, BigDecimal shippingFee,
                            Instant updatedAt) { }
}
