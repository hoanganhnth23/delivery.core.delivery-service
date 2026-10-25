package com.delivery.delivery_service.service;

import com.delivery.delivery_service.controller.InternalOrderCancellationController;
import com.delivery.delivery_service.entity.Delivery;
import com.delivery.delivery_service.entity.DeliveryStatus;
import com.delivery.delivery_service.repository.DeliveryRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OrderCancellationHoldServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"), ZoneOffset.UTC);
    private final DeliveryRepository repository = mock(DeliveryRepository.class);
    private final OrderCancellationHoldService service = new OrderCancellationHoldService(repository, 120, CLOCK);

    @Test
    void assignedDeliveryIsHeldSoTheShipperCannotPickUpMeanwhile() {
        Delivery delivery = delivery(DeliveryStatus.ASSIGNED);
        when(repository.findByOrderIdForUpdate(20L)).thenReturn(Optional.of(delivery));

        OrderCancellationHoldService.Decision decision = service.requestHold(20L);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.holdUntil()).isEqualTo(LocalDateTime.of(2026, 10, 25, 12, 2));
        assertThat(delivery.getCancelHoldUntil()).isEqualTo(decision.holdUntil());
        verify(repository).save(delivery);
    }

    @Test
    void pickedUpDeliveryRefusesTheCancellationAndIsNotTouched() {
        Delivery delivery = delivery(DeliveryStatus.PICKED_UP);
        when(repository.findByOrderIdForUpdate(20L)).thenReturn(Optional.of(delivery));

        OrderCancellationHoldService.Decision decision = service.requestHold(20L);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.deliveryStatus()).isEqualTo("PICKED_UP");
        assertThat(decision.message()).startsWith("Shipper đã lấy hàng");
        assertThat(delivery.getCancelHoldUntil()).isNull();
        verify(repository, never()).save(any());
    }

    @Test
    void noDeliveryOrAlreadyCancelledNeedsNoHold() {
        when(repository.findByOrderIdForUpdate(20L)).thenReturn(Optional.empty());
        assertThat(service.requestHold(20L).allowed()).isTrue();
        Delivery cancelled = delivery(DeliveryStatus.CANCELLED);
        when(repository.findByOrderIdForUpdate(21L)).thenReturn(Optional.of(cancelled));
        assertThat(service.requestHold(21L).allowed()).isTrue();
        assertThat(cancelled.getCancelHoldUntil()).isNull();
        verify(repository, never()).save(any());
        assertThatThrownBy(() -> service.requestHold(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderCancellationHoldService(repository, 0, CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void endpointNeedsTheSharedSecretAndAnswersConflictWhenRefused() {
        var controller = new InternalOrderCancellationController(service, "shared-secret");
        assertThat(controller.requestCancellationHold(20L, null).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.requestCancellationHold(20L, "wrong").getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(repository);

        when(repository.findByOrderIdForUpdate(20L)).thenReturn(Optional.of(delivery(DeliveryStatus.DELIVERING)));
        assertThat(controller.requestCancellationHold(20L, "shared-secret").getStatusCode().value()).isEqualTo(409);
        when(repository.findByOrderIdForUpdate(21L)).thenReturn(Optional.of(delivery(DeliveryStatus.ASSIGNED)));
        assertThat(controller.requestCancellationHold(21L, "shared-secret").getStatusCode().value()).isEqualTo(200);
    }

    private Delivery delivery(DeliveryStatus status) {
        Delivery delivery = new Delivery();
        delivery.setId(1L);
        delivery.setOrderId(20L);
        delivery.setStatus(status);
        return delivery;
    }
}
