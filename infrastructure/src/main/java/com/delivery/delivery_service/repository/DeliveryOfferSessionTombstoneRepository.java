package com.delivery.delivery_service.repository;

import com.delivery.delivery_service.entity.DeliveryOfferSessionTombstone;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryOfferSessionTombstoneRepository extends JpaRepository<DeliveryOfferSessionTombstone, Long> {
    boolean existsByDeliveryIdAndMatchingSessionId(Long deliveryId, String matchingSessionId);
}
