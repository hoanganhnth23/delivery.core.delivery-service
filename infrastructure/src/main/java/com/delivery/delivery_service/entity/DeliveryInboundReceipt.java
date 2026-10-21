package com.delivery.delivery_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Durable Kafka inbox identity for a Saga command applied by Delivery.
 *
 * <p>The Delivery aggregate has state-specific idempotency fences, but they
 * cannot distinguish every exact command replay from a contradictory reuse of
 * the same event identity. This receipt is committed in the same transaction
 * as the Delivery mutation and any correlated outbox failure.</p>
 */
@Entity
@Table(name = "delivery_inbound_receipts", indexes = {
        @Index(name = "idx_delivery_inbound_receipts_order", columnList = "order_id,received_at")
})
@Getter
@NoArgsConstructor
public class DeliveryInboundReceipt {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "command_type", nullable = false, updatable = false, length = 64)
    private String commandType;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "delivery_id", updatable = false)
    private Long deliveryId;

    @Column(name = "payload_fingerprint", nullable = false, updatable = false, length = 64)
    private String payloadFingerprint;

    @Column(name = "received_at", nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    public DeliveryInboundReceipt(UUID eventId, String commandType, Long orderId,
                                  Long deliveryId, String payloadFingerprint) {
        this.eventId = eventId;
        this.commandType = commandType;
        this.orderId = orderId;
        this.deliveryId = deliveryId;
        this.payloadFingerprint = payloadFingerprint;
        this.receivedAt = LocalDateTime.now();
    }
}
