package com.delivery.delivery_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "delivery_batches", indexes = {
        @Index(name = "idx_delivery_batches_shipper", columnList = "shipper_id,status,updated_at")
})
@Getter
@Setter
@NoArgsConstructor
public class DeliveryBatch {

    @Id
    @Column(name = "batch_id", nullable = false, updatable = false)
    private UUID batchId;

    @Column(name = "shipper_id")
    private Long shipperId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private DeliveryBatchStatus status = DeliveryBatchStatus.OFFERED;

    @Column(name = "offer_expires_at")
    private LocalDateTime offerExpiresAt;

    @Column(name = "route_version", nullable = false)
    private int routeVersion;

    @Column(name = "total_cod_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalCodAmount = BigDecimal.ZERO;

    /** Comma-separated UUIDs owned by Settlement; kept additive for migration compatibility. */
    @Column(name = "cod_hold_ids", columnDefinition = "TEXT")
    private String codHoldIds;

    @Column(name = "wave_number", nullable = false)
    private int waveNumber;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
