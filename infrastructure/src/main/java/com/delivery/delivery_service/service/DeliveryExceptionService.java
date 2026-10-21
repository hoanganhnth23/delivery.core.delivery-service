package com.delivery.delivery_service.service;

import com.delivery.delivery_service.common.constants.RoleConstants;
import com.delivery.delivery_service.dto.event.DeliveryExceptionReportedEvent;
import com.delivery.delivery_service.dto.response.DeliveryExceptionResponse;
import com.delivery.delivery_service.entity.Delivery;
import com.delivery.delivery_service.entity.DeliveryException;
import com.delivery.delivery_service.entity.DeliveryExceptionStatus;
import com.delivery.delivery_service.entity.DeliveryStatus;
import com.delivery.delivery_service.exception.ResourceNotFoundException;
import com.delivery.delivery_service.repository.DeliveryExceptionRepository;
import com.delivery.delivery_service.repository.DeliveryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import com.delivery.delivery.domain.DeliveryExceptionPolicy;
import com.delivery.delivery.domain.DeliveryAccessPolicy;
import static com.delivery.delivery_service.service.DeliveryPolicyAdapter.*;
import java.util.UUID;

/**
 * Explicit post-pickup failure workflow. RETURNING/RETURNED are only persisted
 * through this service and never sent through delivery.status-updated.
 */
@Slf4j
@Service
public class DeliveryExceptionService {

    static final int RETRY_WINDOW_MINUTES = DeliveryExceptionPolicy.RETRY_WINDOW_MINUTES;
    private static final int SWEEP_LIMIT = 100;

    private final DeliveryRepository deliveryRepository;
    private final DeliveryExceptionRepository exceptionRepository;
    private final DeliveryEventPublisher eventPublisher;
    private final DeliveryBatchProgressService batchProgressService;
    private final ShipperIdentityResolver shipperIdentityResolver;

    @Value("${delivery.exception.enabled:false}")
    private boolean exceptionEnabled;

    public DeliveryExceptionService(DeliveryRepository deliveryRepository,
                                    DeliveryExceptionRepository exceptionRepository,
                                    DeliveryEventPublisher eventPublisher,
                                    DeliveryBatchProgressService batchProgressService,
                                    ShipperIdentityResolver shipperIdentityResolver) {
        this.deliveryRepository = deliveryRepository;
        this.exceptionRepository = exceptionRepository;
        this.eventPublisher = eventPublisher;
        this.batchProgressService = batchProgressService;
        this.shipperIdentityResolver = shipperIdentityResolver;
    }

    @Transactional
    public DeliveryExceptionResponse reportFailure(Long deliveryId,
                                                    String reason,
                                                    Long principalId,
                                                    Long legacyUserId,
                                                    String role) {
        requireEnabled();
        String normalizedReason = requireReason(reason);
        Delivery delivery = findDeliveryForUpdate(deliveryId);
        Long shipperId = requireAssignedShipper(delivery, principalId, legacyUserId, role);
        DeliveryException existing = exceptionRepository.findByDeliveryIdForUpdate(deliveryId).orElse(null);
        DeliveryExceptionPolicy.Report report = decision(() -> DeliveryExceptionPolicy.onReport(existing != null,
                existing == null ? null : domain(existing.getStatus()), existing == null ? null : existing.getReason(),
                existing == null ? null : existing.getRetryDeadlineAt(), normalizedReason, LocalDateTime.now()));
        if (report == DeliveryExceptionPolicy.Report.RETURN_EXISTING) return toResponse(existing);
        if (report == DeliveryExceptionPolicy.Report.BEGIN_RETURN) return toResponse(beginReturn(delivery, existing));
        requirePostPickup(delivery);

        LocalDateTime now = LocalDateTime.now();
        DeliveryException exceptionCase = new DeliveryException();
        exceptionCase.setExceptionId(UUID.randomUUID());
        exceptionCase.setDeliveryId(delivery.getId());
        exceptionCase.setOrderId(delivery.getOrderId());
        exceptionCase.setShipperId(shipperId);
        exceptionCase.setCustomerId(delivery.getCreatorId());
        exceptionCase.setCustomerPrincipalId(delivery.getCustomerPrincipalId());
        exceptionCase.setRestaurantId(delivery.getRestaurantId());
        exceptionCase.setReason(normalizedReason);
        exceptionCase.setStatus(DeliveryExceptionStatus.RETRY_AVAILABLE);
        exceptionCase.setReportedAt(now);
        exceptionCase.setRetryDeadlineAt(DeliveryExceptionPolicy.retryDeadline(now));
        exceptionRepository.save(exceptionCase);
        eventPublisher.publishDeliveryExceptionReported(toReportedEvent(delivery, exceptionCase));
        return toResponse(exceptionCase);
    }

    @Transactional
    public DeliveryExceptionResponse useRetry(Long deliveryId,
                                               Long principalId,
                                               Long legacyUserId,
                                               String role) {
        requireEnabled();
        Delivery delivery = findDeliveryForUpdate(deliveryId);
        requireAssignedShipper(delivery, principalId, legacyUserId, role);
        DeliveryException exceptionCase = exceptionRepository.findByDeliveryIdForUpdate(deliveryId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sự cố giao hàng"));
        DeliveryExceptionPolicy.Retry retry = decision(() -> DeliveryExceptionPolicy.onUseRetry(
                domain(exceptionCase.getStatus()), exceptionCase.getRetryDeadlineAt(), LocalDateTime.now()));
        if (retry == DeliveryExceptionPolicy.Retry.REPLAY) return toResponse(exceptionCase);
        if (retry == DeliveryExceptionPolicy.Retry.BEGIN_RETURN) return toResponse(beginReturn(delivery, exceptionCase));
        requirePostPickup(delivery);
        exceptionCase.setStatus(DeliveryExceptionStatus.RETRY_USED);
        exceptionCase.setRetryUsedAt(LocalDateTime.now());
        exceptionRepository.save(exceptionCase);
        publishExceptionUpdate(delivery, exceptionCase);
        return toResponse(exceptionCase);
    }

    @Transactional
    public DeliveryExceptionResponse confirmReturn(Long deliveryId,
                                                    Long principalId,
                                                    Long legacyUserId,
                                                    String role) {
        requireEnabled();
        Delivery delivery = findDeliveryForUpdate(deliveryId);
        requireRestaurantOwner(delivery, principalId, legacyUserId, role);
        DeliveryException exceptionCase = exceptionRepository.findByDeliveryIdForUpdate(deliveryId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sự cố giao hàng"));
        if (decision(() -> DeliveryExceptionPolicy.onConfirmReturn(domain(exceptionCase.getStatus()),
                domain(delivery.getStatus()))) == DeliveryExceptionPolicy.ConfirmReturn.REPLAY) return toResponse(exceptionCase);

        LocalDateTime now = LocalDateTime.now();
        exceptionCase.setStatus(DeliveryExceptionStatus.RETURNED);
        exceptionCase.setReturnedAt(now);
        exceptionCase.setReturnedByPrincipalId(principalId);
        delivery.setStatus(DeliveryStatus.RETURNED);
        delivery.setUpdatedAt(now);
        deliveryRepository.save(delivery);
        exceptionRepository.save(exceptionCase);
        publishExceptionUpdate(delivery, exceptionCase);

        boolean routeTerminal = batchProgressService == null
                || batchProgressService.applyExceptionReturn(delivery, true);
        if (DeliveryExceptionPolicy.releaseShipper(routeTerminal, delivery.getShipperId())) {
            if (delivery.getBatchId() == null) {
                publishShipperStatusChange(
                        delivery.getShipperId(), "AVAILABLE", delivery.getId(), delivery.getOrderId(),
                        null, delivery.getSimulationContext());
            } else {
                publishShipperStatusChange(
                        delivery.getShipperId(), "AVAILABLE", delivery.getId(), delivery.getOrderId(), delivery.getBatchId(),
                        delivery.getSimulationContext());
            }
        }
        return toResponse(exceptionCase);
    }

    @Transactional(readOnly = true)
    public DeliveryExceptionResponse getException(Long deliveryId,
                                                  Long principalId,
                                                  Long legacyUserId,
                                                  String role) {
        requireEnabled();
        policy(() -> DeliveryExceptionPolicy.requireDeliveryId(deliveryId));
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thông tin giao hàng với ID: " + deliveryId));
        requireViewer(delivery, principalId, legacyUserId, role);
        return exceptionRepository.findByDeliveryId(deliveryId)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sự cố giao hàng"));
    }

    /** Scheduler entry point; each expired unused retry becomes a return. */
    @Transactional
    public int expireRetryWindows() {
        if (!exceptionEnabled) return 0;
        LocalDateTime now = LocalDateTime.now();
        List<DeliveryException> candidates = exceptionRepository.findRetryDeadlineExpiredForUpdate(
                DeliveryExceptionStatus.RETRY_AVAILABLE, now, PageRequest.of(0, SWEEP_LIMIT));
        int transitioned = 0;
        for (DeliveryException candidate : candidates) {
            try {
                // Keep the lock order identical to reportFailure/useRetry:
                // Delivery first, then its exception row. The initial scan is
                // deliberately unlocked so a concurrent shipper command cannot
                // deadlock the expiry worker.
                Delivery delivery = deliveryRepository.findByIdForUpdate(candidate.getDeliveryId())
                        .orElseThrow(() -> new IllegalStateException("Exception delivery is missing"));
                DeliveryException exceptionCase = exceptionRepository.findByIdForUpdate(candidate.getExceptionId())
                        .orElseThrow(() -> new IllegalStateException("Delivery exception disappeared"));
                DeliveryExceptionPolicy.Sweep sweep = DeliveryExceptionPolicy.onRetryWindowSweep(
                        domain(exceptionCase.getStatus()), exceptionCase.getRetryDeadlineAt(), domain(delivery.getStatus()), now);
                if (sweep == DeliveryExceptionPolicy.Sweep.SKIP) continue;
                if (sweep == DeliveryExceptionPolicy.Sweep.RESOLVE) {
                    exceptionCase.setStatus(DeliveryExceptionStatus.RESOLVED);
                    exceptionRepository.save(exceptionCase);
                    continue;
                }
                if (beginReturn(delivery, exceptionCase).getStatus() == DeliveryExceptionStatus.RETURNING) {
                    transitioned++;
                }
            } catch (RuntimeException failure) {
                // A malformed historic row must remain visible/retryable; do not
                // mark it returned based on an incomplete delivery snapshot.
                log.warn("Unable to expire retry window for delivery exception {}", candidate.getExceptionId(), failure);
            }
        }
        return transitioned;
    }

    /** Called from the normal DELIVERED transaction so an old retry timer cannot return a delivered order. */
    @Transactional
    public void markResolvedAfterSuccessfulDelivery(Delivery delivery) {
        if (!exceptionEnabled || delivery == null || delivery.getId() == null) return;
        DeliveryException exceptionCase = exceptionRepository.findByDeliveryIdForUpdate(delivery.getId()).orElse(null);
        if (decision(() -> DeliveryExceptionPolicy.onSuccessfulDelivery(
                exceptionCase == null ? null : domain(exceptionCase.getStatus()))) == DeliveryExceptionPolicy.Delivered.RESOLVE) {
            exceptionCase.setStatus(DeliveryExceptionStatus.RESOLVED);
            exceptionRepository.save(exceptionCase);
            publishExceptionUpdate(delivery, exceptionCase);
        }
    }

    private DeliveryException beginReturn(Delivery delivery, DeliveryException exceptionCase) {
        if (decision(() -> DeliveryExceptionPolicy.onBeginReturn(domain(exceptionCase.getStatus())))
                == DeliveryExceptionPolicy.BeginReturn.ALREADY_RETURNING) return exceptionCase;
        requirePostPickup(delivery);
        LocalDateTime now = LocalDateTime.now();
        exceptionCase.setStatus(DeliveryExceptionStatus.RETURNING);
        exceptionCase.setReturningAt(now);
        delivery.setStatus(DeliveryStatus.RETURNING);
        delivery.setUpdatedAt(now);
        deliveryRepository.save(delivery);
        exceptionRepository.save(exceptionCase);
        publishExceptionUpdate(delivery, exceptionCase);
        if (batchProgressService != null) {
            batchProgressService.applyExceptionReturn(delivery, false);
        }
        return exceptionCase;
    }

    private Delivery findDeliveryForUpdate(Long deliveryId) {
        policy(() -> DeliveryExceptionPolicy.requireDeliveryId(deliveryId));
        return deliveryRepository.findByIdForUpdate(deliveryId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thông tin giao hàng với ID: " + deliveryId));
    }

    private Long requireAssignedShipper(Delivery delivery, Long principalId, Long legacyUserId, String role) {
        Long shipperId = shipperIdentityResolver.resolveShipperId(principalId, legacyUserId, role);
        policy(() -> DeliveryExceptionPolicy.requireAssignedShipper(shipperId, delivery.getShipperId()));
        return shipperId;
    }

    private void requireRestaurantOwner(Delivery delivery, Long principalId, Long legacyUserId, String role) {
        policy(() -> DeliveryExceptionPolicy.requireRestaurantOwner(RoleConstants.RESTAURANT_OWNER.equals(role),
                principalId, legacyUserId, delivery.getRestaurantOwnerPrincipalId(), delivery.getRestaurantOwnerId()));
    }

    private void requireViewer(Delivery delivery, Long principalId, Long legacyUserId, String role) {
        policy(() -> DeliveryAccessPolicy.requireViewer(viewer(role), principalId, legacyUserId,
                delivery.getCustomerPrincipalId(), delivery.getCreatorId(), delivery.getRestaurantOwnerPrincipalId(),
                delivery.getRestaurantOwnerId(), () -> requireAssignedShipper(delivery, principalId, legacyUserId, role),
                "Bạn không có quyền xem sự cố giao hàng"));
    }

    private void requirePostPickup(Delivery delivery) {
        policy(() -> DeliveryExceptionPolicy.requirePostPickup(domain(delivery.getStatus())));
    }

    private String requireReason(String reason) {
        return decision(() -> DeliveryExceptionPolicy.requireReason(reason));
    }

    private void requireEnabled() {
        policy(() -> DeliveryExceptionPolicy.requireEnabled(exceptionEnabled));
    }

    private DeliveryExceptionReportedEvent toReportedEvent(Delivery delivery, DeliveryException exceptionCase) {
        BigDecimal subtotal = delivery.getSubtotalPrice();
        // The exception contract uses the gross shipping fee and the complete
        // discount delta. customerShippingFee is already net of freeship and
        // itemDiscount already includes shop-funded item discounts, so using
        // either together with their component discounts double-counts them.
        BigDecimal shipping = DeliveryExceptionPolicy.shippingSnapshot(delivery.getGrossShippingFee(), delivery.getShippingFee());
        BigDecimal total = delivery.getTotalPrice();
        BigDecimal discount = decision(() -> DeliveryExceptionPolicy.requireMoneySnapshot(subtotal, shipping, total));
        UUID eventId = UUID.nameUUIDFromBytes(("delivery-exception-reported:" + exceptionCase.getExceptionId())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return DeliveryExceptionReportedEvent.builder()
                .eventId(eventId)
                .eventType("DELIVERY_EXCEPTION_REPORTED")
                .occurredAt(exceptionCase.getReportedAt())
                .exceptionId(exceptionCase.getExceptionId())
                .deliveryId(delivery.getId())
                .orderId(delivery.getOrderId())
                .userId(delivery.getCreatorId())
                .userPrincipalId(delivery.getCustomerPrincipalId())
                .restaurantId(delivery.getRestaurantId())
                .shipperId(delivery.getShipperId())
                .previousDeliveryStatus(delivery.getStatus().name())
                .currentDeliveryStatus(delivery.getStatus().name())
                .exceptionStatus(exceptionCase.getStatus().name())
                .reason(exceptionCase.getReason())
                .paymentMethod(delivery.getPaymentMethod())
                .subtotalPrice(subtotal)
                .discountAmount(discount)
                .shippingFee(shipping)
                .totalPrice(total)
                .build();
    }

    private void publishExceptionUpdate(Delivery delivery, DeliveryException exceptionCase) {
        DeliveryExceptionReportedEvent event = toReportedEvent(delivery, exceptionCase);
        event.setEventType("DELIVERY_EXCEPTION_UPDATED");
        event.setEventId(UUID.nameUUIDFromBytes(("delivery-exception-updated:"
                + exceptionCase.getExceptionId() + ":" + exceptionCase.getStatus())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        eventPublisher.publishDeliveryExceptionUpdated(event);
    }

    private void publishShipperStatusChange(Long shipperId, String status, Long deliveryId, Long orderId,
                                            UUID batchId, com.delivery.identity.contracts.SimulationContext context) {
        com.delivery.identity.contracts.SimulationContext normalized =
                com.delivery.identity.contracts.SimulationContext.orReal(context);
        normalized.requireValid();
        if (normalized.isSimulation()) {
            eventPublisher.publishShipperStatusChange(shipperId, status, deliveryId, orderId, batchId, normalized);
        } else if (batchId == null) {
            eventPublisher.publishShipperStatusChange(shipperId, status, deliveryId, orderId);
        } else {
            eventPublisher.publishShipperStatusChange(shipperId, status, deliveryId, orderId, batchId);
        }
    }

    private DeliveryExceptionResponse toResponse(DeliveryException exceptionCase) {
        DeliveryExceptionResponse response = new DeliveryExceptionResponse();
        response.setExceptionId(exceptionCase.getExceptionId());
        response.setDeliveryId(exceptionCase.getDeliveryId());
        response.setStatus(exceptionCase.getStatus());
        response.setReason(exceptionCase.getReason());
        response.setReportedAt(exceptionCase.getReportedAt());
        response.setRetryDeadlineAt(exceptionCase.getRetryDeadlineAt());
        response.setRetryUsedAt(exceptionCase.getRetryUsedAt());
        response.setReturningAt(exceptionCase.getReturningAt());
        response.setReturnedAt(exceptionCase.getReturnedAt());
        return response;
    }
}
