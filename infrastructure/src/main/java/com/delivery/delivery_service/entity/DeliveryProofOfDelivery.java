package com.delivery.delivery_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Durable metadata for a private proof object. The object URL is deliberately
 * never persisted: callers obtain short-lived signed URLs from the storage
 * adapter after authorization.
 */
@Getter
@Setter
@Entity
@Table(name = "delivery_proofs", indexes = {
        @Index(name = "idx_delivery_proofs_delivery_status", columnList = "delivery_id,status,confirmed_at"),
        @Index(name = "idx_delivery_proofs_retention", columnList = "status,retention_expires_at")
})
public class DeliveryProofOfDelivery {

    @Id
    @Column(name = "proof_id", nullable = false, updatable = false)
    private UUID proofId;

    @Column(name = "delivery_id", nullable = false, updatable = false)
    private Long deliveryId;

    @Column(name = "shipper_id", nullable = false, updatable = false)
    private Long shipperId;

    @Column(name = "storage_provider", nullable = false, updatable = false, length = 80)
    private String storageProvider;

    @Column(name = "object_key", nullable = false, updatable = false, unique = true, length = 512)
    private String objectKey;

    @Column(name = "content_type", nullable = false, length = 128)
    private String contentType;

    @Column(name = "declared_size_bytes", nullable = false, updatable = false)
    private long declaredSizeBytes;

    @Column(name = "verified_size_bytes")
    private Long verifiedSizeBytes;

    @Column(name = "object_checksum", length = 128)
    private String objectChecksum;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DeliveryProofStatus status;

    @Column(name = "upload_expires_at", nullable = false)
    private LocalDateTime uploadExpiresAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "retention_expires_at")
    private LocalDateTime retentionExpiresAt;

    @Column(name = "purged_at")
    private LocalDateTime purgedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
