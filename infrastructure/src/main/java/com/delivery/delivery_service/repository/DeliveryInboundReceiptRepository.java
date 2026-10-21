package com.delivery.delivery_service.repository;

import com.delivery.delivery_service.entity.DeliveryInboundReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface DeliveryInboundReceiptRepository
        extends JpaRepository<DeliveryInboundReceipt, UUID> {

    /**
     * PostgreSQL makes an in-flight conflicting insert wait; after the winning
     * transaction commits this returns zero instead of throwing. If it rolls
     * back, this insert becomes the winner. That is the required concurrency
     * primitive for Kafka redelivery across replicas.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO delivery_inbound_receipts (
                event_id, command_type, order_id, delivery_id, payload_fingerprint, received_at
            ) VALUES (
                :eventId, :commandType, :orderId, :deliveryId, :payloadFingerprint, CURRENT_TIMESTAMP
            ) ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsentPostgres(
            @Param("eventId") UUID eventId,
            @Param("commandType") String commandType,
            @Param("orderId") Long orderId,
            @Param("deliveryId") Long deliveryId,
            @Param("payloadFingerprint") String payloadFingerprint);

    /**
     * H2 does not implement PostgreSQL ON CONFLICT syntax. This test-only
     * fallback preserves first-claim versus replay semantics; production uses
     * insertIfAbsentPostgres for actual concurrent consumer safety.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO delivery_inbound_receipts (
                event_id, command_type, order_id, delivery_id, payload_fingerprint, received_at
            ) SELECT
                :eventId, :commandType, :orderId, :deliveryId, :payloadFingerprint, CURRENT_TIMESTAMP
            WHERE NOT EXISTS (
                SELECT 1 FROM delivery_inbound_receipts WHERE event_id = :eventId
            )
            """, nativeQuery = true)
    int insertIfAbsentH2(
            @Param("eventId") UUID eventId,
            @Param("commandType") String commandType,
            @Param("orderId") Long orderId,
            @Param("deliveryId") Long deliveryId,
            @Param("payloadFingerprint") String payloadFingerprint);
}
