package com.delivery.delivery_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Local read projection; Delivery never calls Shipper/Auth to authorize a request. */
@Entity
@Getter
@Setter
@Table(name = "shipper_identity_projection")
public class ShipperIdentityProjection {
    @Id
    @Column(name = "principal_id", nullable = false, updatable = false)
    private Long principalId;
    @Column(name = "legacy_user_id", nullable = false, unique = true)
    private Long legacyUserId;
    @Column(name = "shipper_id", nullable = false, unique = true)
    private Long shipperId;
    @Column(name = "mapping_version", nullable = false)
    private Long mappingVersion;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
