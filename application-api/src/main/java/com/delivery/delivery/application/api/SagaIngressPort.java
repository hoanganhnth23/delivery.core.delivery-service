package com.delivery.delivery.application.api;

import com.delivery.delivery.domain.DeliveryStatus;

import java.math.BigDecimal;

/** Inbound application boundary for saga commands; adapters translate Kafka/HTTP here. */
public interface SagaIngressPort {
    void createDelivery(CreateDeliveryCommand command);
    void updateStatus(UpdateDeliveryStatusCommand command);
    void cancel(CancelDeliveryCommand command);

    record CreateDeliveryCommand(Long orderId, Long userId, Long restaurantId, BigDecimal shippingFee) { }
    record UpdateDeliveryStatusCommand(Long deliveryId, DeliveryStatus status, String commandId) { }
    record CancelDeliveryCommand(Long deliveryId, Long orderId, String reason, String commandId) { }
}
