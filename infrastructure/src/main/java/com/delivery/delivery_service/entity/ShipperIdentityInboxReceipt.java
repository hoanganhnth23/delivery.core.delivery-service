package com.delivery.delivery_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "shipper_identity_inbox_receipts")
public class ShipperIdentityInboxReceipt {
    @Id @Column(name = "event_id") private UUID eventId;
    @Column(name = "event_type", nullable = false) private String eventType;
    @Column(name = "principal_id", nullable = false) private Long principalId;
    @Column(name = "payload_fingerprint", nullable = false, length = 64) private String payloadFingerprint;
    @Column(name = "processed_at", nullable = false) private LocalDateTime processedAt;
}
