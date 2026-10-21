package com.delivery.delivery_service.listener;

import com.delivery.delivery_service.dto.event.OrderCreatedEvent;
import com.delivery.delivery_service.dto.event.OrderCancelledEvent;
import com.delivery.delivery_service.dto.event.ShipperFoundEvent;
import com.delivery.delivery_service.dto.event.ShipperNotFoundEvent;
import com.delivery.delivery_service.dto.event.ExpireShipperOfferCommand;
import com.delivery.delivery_service.service.DeliverySagaCommandProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.retry.annotation.Backoff;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ✅ Saga Command Listener — Nhận lệnh từ Saga Orchestrator
 *
 * TRƯỚC: Nghe trực tiếp order.created, shipper.found, shipper.not-found
 * SAU:   Chỉ nghe saga.command.* từ Saga Orchestrator
 */
@Slf4j
@Component
@RetryableTopic(
        attempts = "${app.kafka.retry.attempts:4}",
        backoff = @Backoff(delayExpression = "${app.kafka.retry.initial-delay-ms:1000}",
                multiplierExpression = "${app.kafka.retry.multiplier:2.0}",
                maxDelayExpression = "${app.kafka.retry.max-delay-ms:10000}"),
        exclude = IllegalArgumentException.class,
        kafkaTemplate = "retryKafkaTemplate",
        autoCreateTopics = "${app.kafka.retry.auto-create-topics:false}",
        dltTopicSuffix = ".DLT")
public class OrderEventListener {

    private final DeliverySagaCommandProcessor commandProcessor;
    private final ObjectMapper objectMapper;

    @Autowired
    public OrderEventListener(DeliverySagaCommandProcessor commandProcessor) {
        this.commandProcessor = commandProcessor;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * ✅ Nhận lệnh từ Saga: Tạo delivery record
     * Sau khi tạo xong → publish delivery.created.result cho Saga
     */
    @KafkaListener(topics = "${app.kafka.topics.create-delivery:saga.command.create-delivery}")
    public void handleCreateDeliveryCommand(
            String message,
            Acknowledgment acknowledgment) {
        OrderCreatedEvent event;
        try {
            event = objectMapper.readValue(message, OrderCreatedEvent.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse create-delivery command", e);
        }
        log.info("📥 [Delivery] Saga command: create-delivery for orderId={}", event.getOrderId());

        if (event.getEventId() == null || event.getOrderId() == null || event.getOrderId() <= 0) {
            throw new IllegalArgumentException(
                    "Invalid create command: stable eventId and positive orderId are required");
        }
        boolean applied = commandProcessor.applyCreate(event, message);
        log.info("✅ [Delivery] {} create-delivery command for orderId={}",
                applied ? "Processed" : "Skipped exact replay of", event.getOrderId());
        // The processor transaction has committed before the listener can ACK.
        acknowledgment.acknowledge();
    }

    /**
     * ✅ Nhận lệnh từ Saga: Huỷ delivery
     */
    @KafkaListener(topics = "${app.kafka.topics.cancel-delivery:saga.command.cancel-delivery}")
    public void handleCancelDeliveryCommand(
            String message,
            Acknowledgment acknowledgment) {

        OrderCancelledEvent event;
        try {
            event = objectMapper.readValue(message, OrderCancelledEvent.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse cancel-delivery command", e);
        }
        log.info("📥 [Delivery] Saga command: cancel-delivery for orderId={}", event.getOrderId());

        if (event.getEventId() == null || event.getOrderId() == null || event.getOrderId() <= 0) {
            throw new IllegalArgumentException(
                    "Invalid cancel command: stable eventId and positive orderId are required");
        }
        boolean applied = commandProcessor.applyCancel(event, message);
        log.info("✅ [Delivery] {} cancel-delivery command for orderId={}",
                applied ? "Processed" : "Skipped exact replay of", event.getOrderId());
        acknowledgment.acknowledge();
    }

    /** Persist the selected offer before notification-service informs the shipper. */
    @KafkaListener(topics = "${app.kafka.topics.cache-shipper-found:saga.command.cache-shipper-found}")
    public void handleCacheShipperOfferCommand(String message, Acknowledgment acknowledgment) throws Exception {
        ShipperFoundEvent event = objectMapper.readValue(message, ShipperFoundEvent.class);
        requireCommandIdentity(event.getEventId(), event.getOrderId(), event.getDeliveryId(), "cache-shipper-found");
        log.info("📥 [Delivery] Saga command: cache-shipper-found for orderId={}", event.getOrderId());
        commandProcessor.applyCacheShipperOffer(event, message);
        acknowledgment.acknowledge();
    }

    @KafkaListener(topics = "${app.kafka.topics.expire-shipper-offer:saga.command.expire-shipper-offer}")
    public void handleExpireShipperOfferCommand(String message, Acknowledgment acknowledgment) throws Exception {
        ExpireShipperOfferCommand command = objectMapper.readValue(message, ExpireShipperOfferCommand.class);
        requireCommandIdentity(command.getEventId(), command.getOrderId(), command.getDeliveryId(), "expire-shipper-offer");
        commandProcessor.applyExpireShipperOffer(command, message);
        acknowledgment.acknowledge();
    }

    /** Apply the terminal no-shipper result without misclassifying it as cancellation. */
    @KafkaListener(topics = "${app.kafka.topics.mark-shipper-not-found:saga.command.mark-shipper-not-found}")
    public void handleMarkShipperNotFoundCommand(String message, Acknowledgment acknowledgment) throws Exception {
        ShipperNotFoundEvent event = objectMapper.readValue(message, ShipperNotFoundEvent.class);
        if (event.getEventId() == null
                || event.getOrderId() == null || event.getOrderId() <= 0
                || event.getDeliveryId() == null || event.getDeliveryId() <= 0) {
            throw new IllegalArgumentException(
                    "Shipper-not-found command requires eventId and positive order/delivery IDs");
        }
        commandProcessor.applyShipperNotFound(event, message);
        acknowledgment.acknowledge();
    }



    private void requireCommandIdentity(java.util.UUID eventId, Long orderId, Long deliveryId,
                                        String commandType) {
        if (eventId == null || orderId == null || orderId <= 0
                || deliveryId == null || deliveryId <= 0) {
            throw new IllegalArgumentException(
                    commandType + " command requires eventId and positive order/delivery IDs");
        }
    }
}
