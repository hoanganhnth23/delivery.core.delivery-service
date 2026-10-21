package com.delivery.delivery_service.repository;

import com.delivery.delivery_service.entity.DeliveryException;
import com.delivery.delivery_service.entity.DeliveryExceptionStatus;
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

public interface DeliveryExceptionRepository extends JpaRepository<DeliveryException, UUID> {

    /** Locks the exception after its delivery row has been locked. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select exceptionCase from DeliveryException exceptionCase where exceptionCase.exceptionId = :exceptionId")
    Optional<DeliveryException> findByIdForUpdate(@Param("exceptionId") UUID exceptionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select exceptionCase from DeliveryException exceptionCase where exceptionCase.deliveryId = :deliveryId")
    Optional<DeliveryException> findByDeliveryIdForUpdate(@Param("deliveryId") Long deliveryId);

    Optional<DeliveryException> findByDeliveryId(Long deliveryId);

    /** Candidate scan only; the service re-locks each row after locking Delivery. */
    @Query("select exceptionCase from DeliveryException exceptionCase where exceptionCase.status = :status "
            + "and exceptionCase.retryDeadlineAt <= :now order by exceptionCase.retryDeadlineAt asc")
    List<DeliveryException> findRetryDeadlineExpiredForUpdate(
            @Param("status") DeliveryExceptionStatus status,
            @Param("now") LocalDateTime now,
            Pageable pageable);
}
