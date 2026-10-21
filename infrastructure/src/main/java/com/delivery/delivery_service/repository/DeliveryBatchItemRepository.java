package com.delivery.delivery_service.repository;

import com.delivery.delivery_service.entity.DeliveryBatchItem;
import com.delivery.delivery_service.entity.DeliveryBatchItemId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface DeliveryBatchItemRepository extends JpaRepository<DeliveryBatchItem, DeliveryBatchItemId> {

    List<DeliveryBatchItem> findByBatchIdOrderByPickupSequenceAsc(UUID batchId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select item from DeliveryBatchItem item where item.batchId = :batchId and item.deliveryId = :deliveryId")
    java.util.Optional<DeliveryBatchItem> findByBatchIdAndDeliveryIdForUpdate(
            @Param("batchId") UUID batchId, @Param("deliveryId") Long deliveryId);
}
