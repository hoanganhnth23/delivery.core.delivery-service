package com.delivery.delivery_service.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Prevents a delayed cache command from resurrecting a retired match session. */
@Entity
@Table(name = "delivery_offer_session_tombstones", uniqueConstraints = @UniqueConstraint(
        name = "uk_delivery_offer_session", columnNames = {"delivery_id", "matching_session_id"}))
@Getter @Setter @NoArgsConstructor
public class DeliveryOfferSessionTombstone {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "delivery_id", nullable = false, updatable = false)
    private Long deliveryId;
    @Column(name = "matching_session_id", nullable = false, updatable = false, length = 36)
    private String matchingSessionId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public DeliveryOfferSessionTombstone(Long deliveryId, String matchingSessionId) {
        this.deliveryId = deliveryId;
        this.matchingSessionId = matchingSessionId;
        this.createdAt = LocalDateTime.now();
    }
}
