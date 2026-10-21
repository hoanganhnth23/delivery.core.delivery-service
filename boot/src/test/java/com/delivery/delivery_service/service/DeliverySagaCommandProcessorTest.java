package com.delivery.delivery_service.service;

import com.delivery.delivery_service.dto.event.OrderCancelledEvent;
import com.delivery.delivery_service.dto.event.OrderCreatedEvent;
import com.delivery.delivery_service.dto.event.ShipperFoundEvent;
import com.delivery.delivery_service.dto.response.DeliveryResponse;
import com.delivery.delivery_service.exception.InvalidStatusException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeliverySagaCommandProcessorTest {

    @Test
    void exactReplaySkipsTheDeliveryMutation() {
        DeliveryInboundReceiptService receipts = mock(DeliveryInboundReceiptService.class);
        DeliveryService deliveryService = mock(DeliveryService.class);
        EventValidationService validation = mock(EventValidationService.class);
        OutboxService outbox = mock(OutboxService.class);
        OrderCancelledEvent event = cancelEvent();
        when(receipts.claim(event.getEventId(), DeliverySagaCommandProcessor.CANCEL_DELIVERY,
                event.getOrderId(), null, "{}")).thenReturn(false);

        assertThat(processor(receipts, deliveryService, validation, outbox).applyCancel(event, "{}")).isFalse();

        verifyNoInteractions(deliveryService, validation, outbox);
    }

    @Test
    void invalidCreateStoresCorrelatedFailureInTheSameCommandTransaction() {
        DeliveryInboundReceiptService receipts = mock(DeliveryInboundReceiptService.class);
        DeliveryService deliveryService = mock(DeliveryService.class);
        EventValidationService validation = mock(EventValidationService.class);
        OutboxService outbox = mock(OutboxService.class);
        OrderCreatedEvent event = createEvent();
        when(receipts.claim(event.getEventId(), DeliverySagaCommandProcessor.CREATE_DELIVERY,
                event.getOrderId(), null, "{}")).thenReturn(true);
        when(validation.validateOrderCreatedEvent(event))
                .thenReturn(EventValidationService.ValidationResult.invalid("bad canonical amount"));

        assertThat(processor(receipts, deliveryService, validation, outbox).applyCreate(event, "{}")).isTrue();

        verify(outbox).saveEvent(eq(event.getEventId()), eq("ORDER"), eq("101"),
                eq("DELIVERY_COMMAND_FAILED"), eq("delivery.created.failed"), eq("101"), any());
        verifyNoInteractions(deliveryService);
    }

    @Test
    void businessCreateRefusalBecomesCorrelatedFailureButTransientFailurePropagates() {
        DeliveryInboundReceiptService receipts = mock(DeliveryInboundReceiptService.class);
        DeliveryService deliveryService = mock(DeliveryService.class);
        EventValidationService validation = mock(EventValidationService.class);
        OutboxService outbox = mock(OutboxService.class);
        OrderCreatedEvent event = createEvent();
        DeliverySagaCommandProcessor processor = processor(receipts, deliveryService, validation, outbox);
        when(receipts.claim(any(), anyString(), anyLong(), any(), anyString())).thenReturn(true);
        when(validation.validateOrderCreatedEvent(event)).thenReturn(EventValidationService.ValidationResult.valid());
        when(deliveryService.createDeliveryFromOrderEvent(event))
                .thenThrow(new InvalidStatusException("already cancelled"));

        assertThat(processor.applyCreate(event, "{\"first\":true}")).isTrue();
        verify(outbox).saveEvent(eq(event.getEventId()), eq("ORDER"), eq("101"),
                eq("DELIVERY_COMMAND_FAILED"), eq("delivery.created.failed"), eq("101"), any());

        OrderCreatedEvent second = createEvent();
        second.setEventId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
        when(validation.validateOrderCreatedEvent(second)).thenReturn(EventValidationService.ValidationResult.valid());
        when(deliveryService.createDeliveryFromOrderEvent(second))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> processor.applyCreate(second, "{\"second\":true}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database unavailable");
    }

    @Test
    void cacheOfferClaimsBeforeCallingTheDeliveryMutation() {
        DeliveryInboundReceiptService receipts = mock(DeliveryInboundReceiptService.class);
        DeliveryService deliveryService = mock(DeliveryService.class);
        EventValidationService validation = mock(EventValidationService.class);
        OutboxService outbox = mock(OutboxService.class);
        ShipperFoundEvent event = new ShipperFoundEvent();
        event.setEventId(UUID.fromString("44444444-4444-4444-4444-444444444444"));
        event.setOrderId(101L);
        event.setDeliveryId(202L);
        when(receipts.claim(event.getEventId(), DeliverySagaCommandProcessor.CACHE_SHIPPER_OFFER,
                event.getOrderId(), event.getDeliveryId(), "{}")).thenReturn(true);

        assertThat(processor(receipts, deliveryService, validation, outbox)
                .applyCacheShipperOffer(event, "{}")).isTrue();

        verify(receipts).claim(event.getEventId(), DeliverySagaCommandProcessor.CACHE_SHIPPER_OFFER,
                event.getOrderId(), event.getDeliveryId(), "{}");
        verify(deliveryService).cacheShipperOffer(event);
        verify(outbox, never()).saveEvent(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    private DeliverySagaCommandProcessor processor(
            DeliveryInboundReceiptService receipts,
            DeliveryService deliveryService,
            EventValidationService validation,
            OutboxService outbox) {
        return new DeliverySagaCommandProcessor(receipts, deliveryService, validation, outbox);
    }

    private OrderCreatedEvent createEvent() {
        OrderCreatedEvent event = new OrderCreatedEvent();
        event.setEventId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        event.setOrderId(101L);
        return event;
    }

    private OrderCancelledEvent cancelEvent() {
        OrderCancelledEvent event = new OrderCancelledEvent();
        event.setEventId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        event.setOrderId(101L);
        return event;
    }
}
