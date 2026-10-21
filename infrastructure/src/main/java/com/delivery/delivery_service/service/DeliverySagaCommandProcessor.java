package com.delivery.delivery_service.service;

import com.delivery.delivery_service.dto.event.ExpireShipperOfferCommand;
import com.delivery.delivery_service.dto.event.OrderCancelledEvent;
import com.delivery.delivery_service.dto.event.OrderCreatedEvent;
import com.delivery.delivery_service.dto.event.ShipperFoundEvent;
import com.delivery.delivery_service.dto.event.ShipperNotFoundEvent;
import com.delivery.delivery_service.dto.response.DeliveryResponse;
import com.delivery.delivery_service.exception.InvalidStatusException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.Map;

/**
 * Applies a validated Saga command in the same transaction as its durable
 * receipt and any local Delivery/outbox effect. The Kafka listener acknowledges
 * only after this processor returns, so an unsuccessful database commit cannot
 * leave a source offset committed without its side effect.
 */
@Service
public class DeliverySagaCommandProcessor {

    public static final String CREATE_DELIVERY = "CREATE_DELIVERY";
    public static final String CANCEL_DELIVERY = "CANCEL_DELIVERY";
    public static final String CACHE_SHIPPER_OFFER = "CACHE_SHIPPER_OFFER";
    public static final String EXPIRE_SHIPPER_OFFER = "EXPIRE_SHIPPER_OFFER";
    public static final String MARK_SHIPPER_NOT_FOUND = "MARK_SHIPPER_NOT_FOUND";

    private final DeliveryInboundReceiptService receipts;
    private final DeliveryService deliveryService;
    private final EventValidationService eventValidationService;
    private final OutboxService outboxService;
    private final DeliveryBatchOfferService batchOfferService;

    @Autowired
    public DeliverySagaCommandProcessor(
            DeliveryInboundReceiptService receipts,
            DeliveryService deliveryService,
            EventValidationService eventValidationService,
            OutboxService outboxService,
            DeliveryBatchOfferService batchOfferService) {
        this.receipts = receipts;
        this.deliveryService = deliveryService;
        this.eventValidationService = eventValidationService;
        this.outboxService = outboxService;
        this.batchOfferService = batchOfferService;
    }

    /** Compatibility constructor retained for focused legacy unit tests. */
    DeliverySagaCommandProcessor(DeliveryInboundReceiptService receipts,
                                  DeliveryService deliveryService,
                                  EventValidationService eventValidationService,
                                  OutboxService outboxService) {
        this(receipts, deliveryService, eventValidationService, outboxService, null);
    }

    /**
     * @return {@code true} after a newly received command is committed;
     * {@code false} for an exact committed replay.
     */
    @Transactional
    public boolean applyCreate(OrderCreatedEvent event, String rawPayload) {
        if (!claim(event.getEventId(), CREATE_DELIVERY, event.getOrderId(), null, rawPayload)) {
            return false;
        }

        EventValidationService.ValidationResult validation =
                eventValidationService.validateOrderCreatedEvent(event);
        if (!validation.isValid()) {
            publishFailure("delivery.created.failed", event.getEventId(), event.getOrderId(),
                    validation.getErrorMessage());
            return true;
        }

        try {
            DeliveryResponse response = deliveryService.createDeliveryFromOrderEvent(event);
            if (response == null || response.getId() == null) {
                throw new IllegalStateException("Delivery creation returned no durable identity");
            }
        } catch (IllegalArgumentException | InvalidStatusException businessFailure) {
            publishFailure("delivery.created.failed", event.getEventId(), event.getOrderId(),
                    businessFailure.getMessage());
        }
        return true;
    }

    @Transactional
    public boolean applyCancel(OrderCancelledEvent event, String rawPayload) {
        if (!claim(event.getEventId(), CANCEL_DELIVERY, event.getOrderId(), null, rawPayload)) {
            return false;
        }
        try {
            deliveryService.cancelDeliveryFromOrderCancelledEvent(event);
        } catch (IllegalArgumentException | InvalidStatusException businessFailure) {
            publishFailure("delivery.cancel.failed", event.getEventId(), event.getOrderId(),
                    businessFailure.getMessage());
        }
        return true;
    }

    @Transactional
    public boolean applyCacheShipperOffer(ShipperFoundEvent event, String rawPayload) {
        if (!claim(event.getEventId(), CACHE_SHIPPER_OFFER, event.getOrderId(),
                event.getDeliveryId(), rawPayload)) {
            return false;
        }
        if (Boolean.TRUE.equals(event.getBatchOffer())) {
            if (batchOfferService == null) throw new InvalidStatusException("Batch offer support is unavailable");
            batchOfferService.apply(event);
        } else {
            deliveryService.cacheShipperOffer(event);
        }
        return true;
    }

    @Transactional
    public boolean applyExpireShipperOffer(ExpireShipperOfferCommand command, String rawPayload) {
        if (!claim(command.getEventId(), EXPIRE_SHIPPER_OFFER, command.getOrderId(),
                command.getDeliveryId(), rawPayload)) {
            return false;
        }
        deliveryService.expireShipperOffer(command);
        return true;
    }

    @Transactional
    public boolean applyShipperNotFound(ShipperNotFoundEvent event, String rawPayload) {
        if (!claim(event.getEventId(), MARK_SHIPPER_NOT_FOUND, event.getOrderId(),
                event.getDeliveryId(), rawPayload)) {
            return false;
        }
        deliveryService.updateDeliveryStatusFromShipperNotFoundEvent(event);
        return true;
    }

    private boolean claim(java.util.UUID eventId, String commandType, Long orderId,
                          Long deliveryId, String rawPayload) {
        return receipts.claim(eventId, commandType, orderId, deliveryId, rawPayload);
    }

    private void publishFailure(String topic, java.util.UUID commandEventId, Long orderId, String reason) {
        Map<String, Object> failure = new HashMap<>();
        failure.put("commandEventId", commandEventId.toString());
        failure.put("orderId", orderId);
        failure.put("success", false);
        failure.put("reason", reason);
        outboxService.saveEvent(commandEventId, "ORDER", orderId.toString(), "DELIVERY_COMMAND_FAILED",
                topic, orderId.toString(), failure);
    }
}
