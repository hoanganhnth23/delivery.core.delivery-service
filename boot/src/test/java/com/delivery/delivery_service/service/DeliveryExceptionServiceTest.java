package com.delivery.delivery_service.service;

import com.delivery.delivery_service.entity.Delivery;
import com.delivery.delivery_service.entity.DeliveryException;
import com.delivery.delivery_service.entity.DeliveryExceptionStatus;
import com.delivery.delivery_service.entity.DeliveryStatus;
import com.delivery.delivery_service.exception.AccessDeniedException;
import com.delivery.delivery_service.repository.DeliveryExceptionRepository;
import com.delivery.delivery_service.repository.DeliveryRepository;
import com.delivery.delivery_service.repository.ShipperIdentityProjectionRepository;
import com.delivery.delivery_service.entity.ShipperIdentityProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryExceptionServiceTest {
    @Mock DeliveryRepository deliveryRepository;
    @Mock DeliveryExceptionRepository exceptionRepository;
    @Mock DeliveryEventPublisher eventPublisher;
    @Mock DeliveryBatchProgressService batchProgressService;
    @Mock ShipperIdentityProjectionRepository projections;
    private ShipperIdentityResolver identityResolver;

    private DeliveryExceptionService service;
    private Delivery delivery;

    @BeforeEach
    void setUp() {
        ShipperIdentityProjection mapping = new ShipperIdentityProjection();
        mapping.setPrincipalId(1007L);
        mapping.setLegacyUserId(107L);
        mapping.setShipperId(7L);
        identityResolver = ShipperIdentityResolver.compatibility(projections, null, false);
        lenient().when(projections.findById(1007L)).thenReturn(Optional.of(mapping));
        service = new DeliveryExceptionService(deliveryRepository, exceptionRepository, eventPublisher,
                batchProgressService, identityResolver);
        ReflectionTestUtils.setField(service, "exceptionEnabled", true);
        delivery = delivery(DeliveryStatus.DELIVERING);
        when(deliveryRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(delivery));
    }

    @Test
    void failureCreatesOneRetryWindowAndDedicatedEvent() {
        when(exceptionRepository.findByDeliveryIdForUpdate(7L)).thenReturn(Optional.empty());

        var response = service.reportFailure(7L, "Customer unavailable", 1007L, 107L, "SHIPPER");

        assertThat(response.getStatus()).isEqualTo(DeliveryExceptionStatus.RETRY_AVAILABLE);
        assertThat(response.getRetryDeadlineAt()).isAfter(LocalDateTime.now().plusMinutes(14));
        ArgumentCaptor<DeliveryException> captor = ArgumentCaptor.forClass(DeliveryException.class);
        verify(exceptionRepository).save(captor.capture());
        assertThat(captor.getValue().getReason()).isEqualTo("Customer unavailable");
        verify(eventPublisher).publishDeliveryExceptionReported(any());
    }

    @Test
    void exceptionEventUsesGrossShippingAndCanonicalDiscountDelta() {
        delivery.setGrossShippingFee(new BigDecimal("25000"));
        delivery.setCustomerShippingFee(new BigDecimal("15000"));
        // itemDiscount includes the shop-funded part; summing both would
        // overstate the total discount. customerShippingFee is already net of
        // the freeship discount as well.
        delivery.setItemDiscount(new BigDecimal("5000"));
        delivery.setShopDiscount(new BigDecimal("2000"));
        delivery.setShippingDiscount(new BigDecimal("10000"));
        delivery.setTotalPrice(new BigDecimal("110000"));
        when(exceptionRepository.findByDeliveryIdForUpdate(7L)).thenReturn(Optional.empty());

        service.reportFailure(7L, "Customer unavailable", 1007L, 107L, "SHIPPER");

        ArgumentCaptor<com.delivery.delivery_service.dto.event.DeliveryExceptionReportedEvent> event =
                ArgumentCaptor.forClass(com.delivery.delivery_service.dto.event.DeliveryExceptionReportedEvent.class);
        verify(eventPublisher).publishDeliveryExceptionReported(event.capture());
        assertThat(event.getValue().getShippingFee()).isEqualByComparingTo("25000");
        assertThat(event.getValue().getDiscountAmount()).isEqualByComparingTo("15000");
        assertThat(event.getValue().getSubtotalPrice().add(event.getValue().getShippingFee())
                .subtract(event.getValue().getDiscountAmount()))
                .isEqualByComparingTo(event.getValue().getTotalPrice());
    }

    @Test
    void secondFailureWithDifferentReasonFailsClosed() {
        DeliveryException existing = exception(DeliveryExceptionStatus.RETRY_AVAILABLE);
        existing.setReason("first reason");
        when(exceptionRepository.findByDeliveryIdForUpdate(7L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.reportFailure(7L, "different reason", 1007L, 107L, "SHIPPER"))
                .isInstanceOf(com.delivery.delivery_service.exception.InvalidStatusException.class)
                .hasMessageContaining("lý do khác");
    }

    @Test
    void usesAtMostOneRetryBeforeMovingToReturn() {
        DeliveryException existing = exception(DeliveryExceptionStatus.RETRY_AVAILABLE);
        existing.setRetryDeadlineAt(LocalDateTime.now().plusMinutes(5));
        when(exceptionRepository.findByDeliveryIdForUpdate(7L)).thenReturn(Optional.of(existing));

        var first = service.useRetry(7L, 1007L, 107L, "SHIPPER");
        assertThat(first.getStatus()).isEqualTo(DeliveryExceptionStatus.RETRY_USED);
        verify(eventPublisher).publishDeliveryExceptionUpdated(any());

        existing.setStatus(DeliveryExceptionStatus.RETRY_USED);
        var duplicate = service.useRetry(7L, 1007L, 107L, "SHIPPER");
        assertThat(duplicate.getStatus()).isEqualTo(DeliveryExceptionStatus.RETRY_USED);
    }

    @Test
    void expiredWindowMovesDeliveryToReturningAndRestaurantConfirmsReturned() {
        DeliveryException existing = exception(DeliveryExceptionStatus.RETRY_AVAILABLE);
        existing.setRetryDeadlineAt(LocalDateTime.now().minusSeconds(1));
        when(exceptionRepository.findRetryDeadlineExpiredForUpdate(eq(DeliveryExceptionStatus.RETRY_AVAILABLE),
                any(LocalDateTime.class), any(Pageable.class))).thenReturn(List.of(existing));
        when(exceptionRepository.findByDeliveryIdForUpdate(7L)).thenReturn(Optional.of(existing));
        when(exceptionRepository.findByIdForUpdate(existing.getExceptionId())).thenReturn(Optional.of(existing));
        when(batchProgressService.applyExceptionReturn(delivery, false)).thenReturn(false);

        assertThat(service.expireRetryWindows()).isEqualTo(1);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.RETURNING);
        assertThat(existing.getStatus()).isEqualTo(DeliveryExceptionStatus.RETURNING);

        when(deliveryRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(delivery));
        assertThatThrownBy(() -> service.confirmReturn(7L, 999L, 999L, "SHOP_OWNER"))
                .isInstanceOf(AccessDeniedException.class);

        delivery.setRestaurantOwnerPrincipalId(2007L);
        when(exceptionRepository.findByDeliveryIdForUpdate(7L)).thenReturn(Optional.of(existing));
        when(batchProgressService.applyExceptionReturn(delivery, true)).thenReturn(true);
        var returned = service.confirmReturn(7L, 2007L, 207L, "SHOP_OWNER");

        assertThat(returned.getStatus()).isEqualTo(DeliveryExceptionStatus.RETURNED);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.RETURNED);
        verify(eventPublisher).publishShipperStatusChange(7L, "AVAILABLE", 7L, 70L);
    }

    @Test
    void repeatedFailureAfterRetryDeadlineMovesCaseToReturningInsteadOfGrantingAnotherWindow() {
        DeliveryException existing = exception(DeliveryExceptionStatus.RETRY_AVAILABLE);
        existing.setRetryDeadlineAt(LocalDateTime.now().minusSeconds(1));
        when(exceptionRepository.findByDeliveryIdForUpdate(7L)).thenReturn(Optional.of(existing));
        when(batchProgressService.applyExceptionReturn(delivery, false)).thenReturn(false);

        var response = service.reportFailure(7L, "Customer unavailable", 1007L, 107L, "SHIPPER");

        assertThat(response.getStatus()).isEqualTo(DeliveryExceptionStatus.RETURNING);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.RETURNING);
        verify(eventPublisher).publishDeliveryExceptionUpdated(any());
    }

    private Delivery delivery(DeliveryStatus status) {
        Delivery result = new Delivery();
        result.setId(7L);
        result.setOrderId(70L);
        result.setShipperId(7L);
        result.setCreatorId(107L);
        result.setCustomerPrincipalId(1007L);
        result.setRestaurantId(17L);
        result.setRestaurantOwnerId(117L);
        result.setStatus(status);
        result.setSubtotalPrice(new BigDecimal("100000"));
        result.setCustomerShippingFee(new BigDecimal("25000"));
        result.setShippingFee(new BigDecimal("25000"));
        result.setItemDiscount(BigDecimal.ZERO);
        result.setShopDiscount(BigDecimal.ZERO);
        result.setShippingDiscount(BigDecimal.ZERO);
        result.setTotalPrice(new BigDecimal("125000"));
        result.setPaymentMethod("COD");
        return result;
    }

    private DeliveryException exception(DeliveryExceptionStatus status) {
        DeliveryException result = new DeliveryException();
        result.setExceptionId(java.util.UUID.randomUUID());
        result.setDeliveryId(7L);
        result.setOrderId(70L);
        result.setShipperId(7L);
        result.setCustomerId(107L);
        result.setCustomerPrincipalId(1007L);
        result.setRestaurantId(17L);
        result.setReason("Customer unavailable");
        result.setStatus(status);
        result.setReportedAt(LocalDateTime.now().minusMinutes(1));
        result.setRetryDeadlineAt(LocalDateTime.now().plusMinutes(10));
        return result;
    }
}
