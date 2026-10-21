package com.delivery.delivery_service.repository;

import com.delivery.delivery_service.entity.DeliveryProofOfDelivery;
import com.delivery.delivery_service.entity.DeliveryProofStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryProofOfDeliveryRepository extends JpaRepository<DeliveryProofOfDelivery, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select proof from DeliveryProofOfDelivery proof where proof.proofId = :proofId")
    Optional<DeliveryProofOfDelivery> findByIdForUpdate(@Param("proofId") UUID proofId);

    boolean existsByDeliveryIdAndStatus(Long deliveryId, DeliveryProofStatus status);

    @Query("select proof from DeliveryProofOfDelivery proof where proof.deliveryId = :deliveryId "
            + "and proof.status = :status order by proof.confirmedAt desc, proof.proofId desc")
    List<DeliveryProofOfDelivery> findByDeliveryIdAndStatus(
            @Param("deliveryId") Long deliveryId,
            @Param("status") DeliveryProofStatus status,
            Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select proof from DeliveryProofOfDelivery proof where proof.status = "
            + "com.delivery.delivery_service.entity.DeliveryProofStatus.CONFIRMED "
            + "and proof.retentionExpiresAt <= :now order by proof.retentionExpiresAt asc")
    List<DeliveryProofOfDelivery> findRetentionExpiredForUpdate(
            @Param("now") LocalDateTime now,
            Pageable pageable);
}
