package com.delivery.delivery_service.service;

import com.delivery.delivery_service.common.constants.KafkaTopicConstants;
import com.delivery.delivery_service.dto.event.ShipperAcceptedEvent;
import com.delivery.delivery_service.dto.event.DeliveryCompletedEvent;
import com.delivery.delivery_service.dto.event.OfferPersistedEvent;
import com.delivery.delivery_service.dto.event.OfferRetiredEvent;
import com.delivery.delivery_service.dto.event.DeliveryExceptionReportedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import com.delivery.identity.contracts.SimulationContext;
import com.delivery.delivery.contracts.DeliveryStatusUpdatedEvent;

/**
 * ✅ Event Publisher — Transactional Outbox Pattern
 *
 * TRƯỚC: kafkaTemplate.send() trực tiếp → mất event nếu server sập
 * SAU:   outboxService.saveEvent() → lưu cùng DB transaction → không bao giờ mất
 *
 * OutboxMessageRelay sẽ poll bảng outbox → gửi lên Kafka
 */
@Slf4j
@Service
public class DeliveryEventPublisher {

    private final OutboxService outboxService;

    @Value("${app.kafka.topics.delivery-status-updated:delivery.status-updated}")
    private String deliveryStatusUpdatedTopic = KafkaTopicConstants.DELIVERY_STATUS_UPDATED_TOPIC;
    @Value("${app.kafka.topics.delivery-completed:delivery.completed}")
    private String deliveryCompletedTopic = KafkaTopicConstants.DELIVERY_COMPLETED_TOPIC;
    @Value("${app.kafka.topics.shipper-status-change:shipper.status-change}")
    private String shipperStatusChangeTopic = KafkaTopicConstants.SHIPPER_STATUS_CHANGE_TOPIC;
    @Value("${app.kafka.topics.offer-persisted:delivery.offer-persisted}")
    private String offerPersistedTopic = KafkaTopicConstants.OFFER_PERSISTED_TOPIC;
    @Value("${app.kafka.topics.offer-retired:delivery.offer-retired}")
    private String offerRetiredTopic = KafkaTopicConstants.OFFER_RETIRED_TOPIC;
    @Value("${app.kafka.topics.delivery-exception-reported:delivery.exception.reported}")
    private String deliveryExceptionReportedTopic = KafkaTopicConstants.DELIVERY_EXCEPTION_REPORTED_TOPIC;

    public DeliveryEventPublisher(OutboxService outboxService) {
        this.outboxService = outboxService;
    }

    /**
     * Gửi ShipperAcceptedEvent
     */
    public void publishShipperAcceptedEvent(ShipperAcceptedEvent event) {
        log.info("📦 [Kafka] Sending ShipperAcceptedEvent for order: {}, shipper: {}",
                event.getOrderId(), event.getShipperId());
        outboxService.saveEvent("ORDER", event.getOrderId().toString(), "SHIPPER_ACCEPTED",
                KafkaTopicConstants.SHIPPER_ACCEPTED_TOPIC, event.getOrderId().toString(), event);
    }

    /**
     * Gửi delivery status update event
     */
    public void publishDeliveryStatusUpdated(Long deliveryId, Long orderId, Long userId, Long userPrincipalId, Long shipperId,
                                             String status, String previousStatus) {
        publishDeliveryStatusUpdated(deliveryId, orderId, userId, userPrincipalId, shipperId,
                status, previousStatus, SimulationContext.real());
    }

    public void publishDeliveryStatusUpdated(Long deliveryId, Long orderId, Long userId, Long userPrincipalId, Long shipperId,
                                             String status, String previousStatus, SimulationContext context) {
        if (deliveryId == null || deliveryId <= 0 || orderId == null || orderId <= 0
                || userId == null || userId <= 0 || status == null || status.isBlank()
                || previousStatus == null || previousStatus.isBlank()) {
            throw new IllegalArgumentException("delivery status event identity and statuses are required");
        }
        log.info("📦 [Kafka] Sending delivery status update: {} -> {} for delivery: {}, order: {}",
                previousStatus, status, deliveryId, orderId);

        DeliveryStatusUpdatedEvent statusEvent = new DeliveryStatusUpdatedEvent(
                UUID.nameUUIDFromBytes(("delivery.status-updated:" + deliveryId + ":" + orderId + ":"
                        + status + ":" + previousStatus).getBytes(StandardCharsets.UTF_8)),
                "DELIVERY_STATUS_UPDATED", LocalDateTime.now(), deliveryId, orderId, userId,
                userPrincipalId, shipperId, status, previousStatus, null, context);

        save(deliveryId, "DELIVERY_STATUS_UPDATED", deliveryStatusUpdatedTopic, statusEvent);
    }

    /** Source-compatible adapter for legacy callers while producers migrate principal identity. */
    public void publishDeliveryStatusUpdated(Long deliveryId, Long orderId, Long userId, Long shipperId,
                                             String status, String previousStatus) {
        publishDeliveryStatusUpdated(deliveryId, orderId, userId, null, shipperId, status, previousStatus);
    }

    /**
     * ✅ Publish DeliveryCompletedEvent — SỰ KIỆN QUAN TRỌNG NHẤT (liên quan đến tiền)
     */
    public void publishDeliveryCompletedEvent(DeliveryCompletedEvent event) {
        log.info("📦 [Kafka] Sending DeliveryCompletedEvent for delivery: {}, shipper: {}, amount: {}",
                event.getDeliveryId(), event.getShipperId(), event.getShippingFee());
        save(event.getDeliveryId(), "DELIVERY_COMPLETED", deliveryCompletedTopic, event);
    }

    /**
     * Publish shipper status change event (BUSY/AVAILABLE)
     */
    public void publishShipperStatusChange(Long shipperId, String status, Long deliveryId, Long orderId) {
        publishShipperStatusChange(shipperId, status, deliveryId, orderId, null);
    }

    public void publishShipperStatusChange(Long shipperId, String status, Long deliveryId, Long orderId,
                                           UUID batchId) {
        publishShipperStatusChange(shipperId, status, deliveryId, orderId, batchId,
                SimulationContext.real());
    }

    public void publishShipperStatusChange(Long shipperId, String status, Long deliveryId, Long orderId,
                                           UUID batchId, SimulationContext context) {
        log.info("📦 [Kafka] Sending shipper status change: shipper={}, status={}", shipperId, status);

        Map<String, Object> event = new HashMap<>();
        event.put("shipperId", shipperId);
        event.put("status", status);
        event.put("deliveryId", deliveryId);
        event.put("orderId", orderId);
        event.put("timestamp", System.currentTimeMillis());
        if (batchId != null) event.put("batchId", batchId.toString());
        SimulationContext normalized = SimulationContext.orReal(context);
        normalized.requireValid();
        event.put("simulationContext", normalized);

        outboxService.saveEvent("DELIVERY", deliveryId.toString(), "SHIPPER_STATUS_CHANGE",
                shipperStatusChangeTopic, shipperId.toString(), event);
    }

    public void publishOfferPersisted(OfferPersistedEvent event) {
        UUID eventId = derivedEventId("delivery.offer-persisted", event.getSourceCommandEventId());
        outboxService.saveEvent(eventId, "DELIVERY", event.getDeliveryId().toString(), "OFFER_PERSISTED",
                offerPersistedTopic, event.getOrderId().toString(), event);
    }

    public void publishOfferRetired(OfferRetiredEvent event) {
        UUID eventId = derivedEventId("delivery.offer-retired", event.getSourceCommandEventId());
        outboxService.saveEvent(eventId, "DELIVERY", event.getDeliveryId().toString(), "OFFER_RETIRED",
                offerRetiredTopic, event.getOrderId().toString(), event);
    }

    /**
     * Emits the post-pickup exception through a dedicated topic. Existing Saga
     * consumers retain their strict legacy status vocabulary on status-updated.
     */
    public void publishDeliveryExceptionReported(DeliveryExceptionReportedEvent event) {
        publishDeliveryExceptionEvent(event);
    }

    public void publishDeliveryExceptionUpdated(DeliveryExceptionReportedEvent event) {
        publishDeliveryExceptionEvent(event);
    }

    private void publishDeliveryExceptionEvent(DeliveryExceptionReportedEvent event) {
        if (event == null || event.getEventId() == null || event.getExceptionId() == null
                || event.getDeliveryId() == null || event.getOrderId() == null) {
            throw new IllegalArgumentException("delivery exception event identity is required");
        }
        String eventType = event.getEventType() == null || event.getEventType().isBlank()
                ? "DELIVERY_EXCEPTION_REPORTED" : event.getEventType();
        outboxService.saveEvent(event.getEventId(), "DELIVERY_EXCEPTION", event.getExceptionId().toString(),
                eventType, deliveryExceptionReportedTopic,
                event.getOrderId().toString(), event);
    }

    private UUID derivedEventId(String type, UUID sourceCommandEventId) {
        if (sourceCommandEventId == null) throw new IllegalArgumentException("sourceCommandEventId is required");
        return UUID.nameUUIDFromBytes((type + ":" + sourceCommandEventId).getBytes(StandardCharsets.UTF_8));
    }

    private void save(Long deliveryId, String eventType, String topic, Object event) {
        if (deliveryId == null) throw new IllegalArgumentException("deliveryId is required");
        outboxService.saveEvent("DELIVERY", deliveryId.toString(), eventType,
                topic, deliveryId.toString(), event);
    }
}
