package com.delivery.delivery_service.repository;

import com.delivery.delivery_service.entity.ShipperIdentityProjection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipperIdentityProjectionRepository extends JpaRepository<ShipperIdentityProjection, Long> {
    Optional<ShipperIdentityProjection> findByLegacyUserId(Long legacyUserId);
}
