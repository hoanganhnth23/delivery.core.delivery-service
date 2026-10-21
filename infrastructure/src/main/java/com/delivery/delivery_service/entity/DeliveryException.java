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
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One durable post-pickup exception case per delivery. It owns the retry and
 * return workflow while the normal delivery lifecycle stays compatible with
 * existing Saga consumers.
 */
@Getter
@Setter
@Entity
@Table(name = "delivery_exceptions", indexes = {
        @Index(name = "idx_delivery_exceptions_retry", columnList = "status,retry_deadline_at"),
        @Index(name = "idx_delivery_exceptions_order", columnList = "order_id,created_at")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_delivery_exceptions_delivery", columnNames = "delivery_id")
})
public class DeliveryException {

    @Id
    @Column(name = "exception_id", nullable = false, updatable = false)
    private UUID exceptionId;

    @Column(name = "delivery_id", nullable = false, updatable = false)
    private Long deliveryId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "shipper_id", nullable = false, updatable = false)
    private Long shipperId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "customer_principal_id", updatable = false)
    private Long customerPrincipalId;

    @Column(name = "restaurant_id", nullable = false, updatable = false)
    private Long restaurantId;

    @Column(name = "reason", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DeliveryExceptionStatus status;

    @Column(name = "reported_at", nullable = false, updatable = false)
    private LocalDateTime reportedAt;

    @Column(name = "retry_deadline_at", nullable = false, updatable = false)
    private LocalDateTime retryDeadlineAt;

    @Column(name = "retry_used_at")
    private LocalDateTime retryUsedAt;

    @Column(name = "returning_at")
    private LocalDateTime returningAt;

    @Column(name = "returned_at")
    private LocalDateTime returnedAt;

    @Column(name = "returned_by_principal_id")
    private Long returnedByPrincipalId;

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
