package com.delivery.delivery_service.repository;

import com.delivery.delivery_service.entity.DeliveryBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;

public interface DeliveryBatchRepository extends JpaRepository<DeliveryBatch, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select batch from DeliveryBatch batch where batch.batchId = :batchId")
    Optional<DeliveryBatch> findByIdForUpdate(@Param("batchId") UUID batchId);

    @Query("select batch from DeliveryBatch batch where batch.shipperId = :shipperId and batch.status = com.delivery.delivery_service.entity.DeliveryBatchStatus.OFFERED and batch.offerExpiresAt > :now order by batch.offerExpiresAt asc")
    List<DeliveryBatch> findCurrentOffersByShipper(@Param("shipperId") Long shipperId,
                                                   @Param("now") LocalDateTime now,
                                                   Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select batch from DeliveryBatch batch where batch.status = com.delivery.delivery_service.entity.DeliveryBatchStatus.OFFERED and batch.offerExpiresAt <= :now order by batch.offerExpiresAt asc")
    List<DeliveryBatch> findExpiredOffersForUpdate(@Param("now") LocalDateTime now, Pageable pageable);
}
