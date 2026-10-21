package com.delivery.delivery_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "delivery_batch_items")
@IdClass(DeliveryBatchItemId.class)
@Getter
@Setter
@NoArgsConstructor
public class DeliveryBatchItem {

    @Id
    @Column(name = "batch_id", nullable = false, updatable = false)
    private UUID batchId;

    @Id
    @Column(name = "delivery_id", nullable = false, updatable = false)
    private Long deliveryId;

    @Column(name = "pickup_sequence", nullable = false)
    private int pickupSequence;

    @Column(name = "dropoff_sequence", nullable = false)
    private int dropoffSequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_status", nullable = false, length = 24)
    private DeliveryBatchItemStatus itemStatus = DeliveryBatchItemStatus.OFFERED;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
