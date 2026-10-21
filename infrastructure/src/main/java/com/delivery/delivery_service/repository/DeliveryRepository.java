package com.delivery.delivery_service.repository;

import com.delivery.delivery_service.entity.Delivery;
import com.delivery.delivery_service.entity.DeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;
import jakarta.persistence.LockModeType;

@Repository
public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    /**
     * Tìm delivery theo order ID
     */
    Optional<Delivery> findByOrderId(Long orderId);

    List<Delivery> findBySimulationRunIdOrderByIdAsc(java.util.UUID simulationRunId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Delivery d WHERE d.id = :deliveryId")
    Optional<Delivery> findByIdForUpdate(@Param("deliveryId") Long deliveryId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Delivery d WHERE d.orderId = :orderId")
    Optional<Delivery> findByOrderIdForUpdate(@Param("orderId") Long orderId);

    List<Delivery> findByShipperIdOrderByCreatedAtDesc(Long shipperId, Pageable pageable);

    /**
     * Lấy delivery đang active của shipper
     */
    @Query("SELECT d FROM Delivery d WHERE d.shipperId = :shipperId AND d.status IN (com.delivery.delivery_service.entity.DeliveryStatus.ASSIGNED, com.delivery.delivery_service.entity.DeliveryStatus.PICKED_UP, com.delivery.delivery_service.entity.DeliveryStatus.DELIVERING, com.delivery.delivery_service.entity.DeliveryStatus.RETURNING) ORDER BY d.createdAt DESC")
    List<Delivery> findActiveDeliveriesByShipper(@Param("shipperId") Long shipperId, Pageable pageable);

    @Query("SELECT d FROM Delivery d WHERE d.offeredShipperId = :shipperId "
            + "AND d.status = com.delivery.delivery_service.entity.DeliveryStatus.WAIT_SHIPPER_CONFIRM "
            + "AND d.offerExpiresAt > :now ORDER BY d.offerExpiresAt ASC, d.id ASC")
    List<Delivery> findCurrentOffersByShipper(
            @Param("shipperId") Long shipperId,
            @Param("now") LocalDateTime now,
            Pageable pageable);
}
