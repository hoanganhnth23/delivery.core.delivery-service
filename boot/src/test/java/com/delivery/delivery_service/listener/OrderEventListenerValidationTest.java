package com.delivery.delivery_service.listener;

import com.delivery.delivery_service.dto.event.ExpireShipperOfferCommand;
import com.delivery.delivery_service.dto.event.OrderCancelledEvent;
import com.delivery.delivery_service.dto.event.OrderCreatedEvent;
import com.delivery.delivery_service.dto.event.ShipperNotFoundEvent;
import com.delivery.delivery_service.service.DeliverySagaCommandProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderEventListenerValidationTest {

    @Test
    void exactSagaCommandReplayAcknowledgesWithoutRepeatingDeliveryMutation() throws Exception {
        DeliverySagaCommandProcessor processor = mock(DeliverySagaCommandProcessor.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        String command = """
                {"eventId":"55555555-5555-5555-5555-555555555555",
                 "orderId":101,"deliveryId":202,"retryAttempts":10}
                """;
        when(processor.applyShipperNotFound(any(ShipperNotFoundEvent.class), eq(command)))
                .thenReturn(false);

        new OrderEventListener(processor).handleMarkShipperNotFoundCommand(command, acknowledgment);

        verify(processor).applyShipperNotFound(any(ShipperNotFoundEvent.class), eq(command));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void contradictorySagaCommandReceiptIsNotAcknowledged() throws Exception {
        DeliverySagaCommandProcessor processor = mock(DeliverySagaCommandProcessor.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        String command = """
                {"eventId":"55555555-5555-5555-5555-555555555555",
                 "orderId":101,"deliveryId":202,"retryAttempts":10}
                """;
        doThrow(new IllegalArgumentException("contradictory command"))
                .when(processor).applyShipperNotFound(any(ShipperNotFoundEvent.class), eq(command));

        assertThrows(IllegalArgumentException.class,
                () -> new OrderEventListener(processor)
                        .handleMarkShipperNotFoundCommand(command, acknowledgment));

        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void validCreateCommandDispatchesThenAcknowledges() throws Exception {
        DeliverySagaCommandProcessor processor = mock(DeliverySagaCommandProcessor.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        OrderCreatedEvent event = new OrderCreatedEvent();
        event.setEventId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        event.setOrderId(101L);
        String command = new ObjectMapper().findAndRegisterModules().writeValueAsString(event);
        when(processor.applyCreate(any(OrderCreatedEvent.class), eq(command))).thenReturn(true);

        new OrderEventListener(processor).handleCreateDeliveryCommand(command, acknowledgment);

        verify(processor).applyCreate(any(OrderCreatedEvent.class), eq(command));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void invalidCreateIdentityDoesNotReachTheProcessorOrAcknowledge() {
        DeliverySagaCommandProcessor processor = mock(DeliverySagaCommandProcessor.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        String command = """
                {"eventId":"11111111-1111-1111-1111-111111111111","orderId":0}
                """;

        assertThrows(IllegalArgumentException.class,
                () -> new OrderEventListener(processor).handleCreateDeliveryCommand(command, acknowledgment));

        verifyNoInteractions(processor, acknowledgment);
    }

    @Test
    void malformedCreateCommandDoesNotReachTheProcessorOrAcknowledge() {
        DeliverySagaCommandProcessor processor = mock(DeliverySagaCommandProcessor.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        assertThrows(IllegalArgumentException.class,
                () -> new OrderEventListener(processor).handleCreateDeliveryCommand("not-json", acknowledgment));

        verifyNoInteractions(processor, acknowledgment);
    }

    @Test
    void cancelCommandDispatchesThenAcknowledges() throws Exception {
        DeliverySagaCommandProcessor processor = mock(DeliverySagaCommandProcessor.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        OrderCancelledEvent event = new OrderCancelledEvent();
        event.setEventId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        event.setOrderId(101L);
        String command = new ObjectMapper().findAndRegisterModules().writeValueAsString(event);
        when(processor.applyCancel(any(OrderCancelledEvent.class), eq(command))).thenReturn(true);

        new OrderEventListener(processor).handleCancelDeliveryCommand(command, acknowledgment);

        verify(processor).applyCancel(any(OrderCancelledEvent.class), eq(command));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void offerTimeoutIsAcknowledgedOnlyAfterProcessorReturns() throws Exception {
        DeliverySagaCommandProcessor processor = mock(DeliverySagaCommandProcessor.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        String command = """
                {"eventId":"44444444-4444-4444-4444-444444444444",
                 "orderId":101,"deliveryId":202,"timedOutShipperId":303,
                 "expectedOfferExpiresAt":"2026-07-25T13:03:00"}
                """;
        when(processor.applyExpireShipperOffer(any(ExpireShipperOfferCommand.class), eq(command)))
                .thenReturn(true);

        new OrderEventListener(processor).handleExpireShipperOfferCommand(command, acknowledgment);

        verify(processor).applyExpireShipperOffer(any(ExpireShipperOfferCommand.class), eq(command));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void malformedShipperNotFoundCommandIsNotAcknowledged() {
        DeliverySagaCommandProcessor processor = mock(DeliverySagaCommandProcessor.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        assertThrows(IllegalArgumentException.class,
                () -> new OrderEventListener(processor).handleMarkShipperNotFoundCommand(
                        "{\"orderId\":101,\"deliveryId\":202}", acknowledgment));

        verifyNoInteractions(processor, acknowledgment);
    }

    @Test
    void acknowledgementFailureAfterCommittedProcessorResultPropagatesForReplay() throws Exception {
        DeliverySagaCommandProcessor processor = mock(DeliverySagaCommandProcessor.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        OrderCancelledEvent event = new OrderCancelledEvent();
        event.setEventId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        event.setOrderId(101L);
        String command = new ObjectMapper().findAndRegisterModules().writeValueAsString(event);
        when(processor.applyCancel(any(OrderCancelledEvent.class), eq(command))).thenReturn(true);
        doThrow(new IllegalStateException("commit offset unavailable")).when(acknowledgment).acknowledge();

        assertThrows(IllegalStateException.class,
                () -> new OrderEventListener(processor).handleCancelDeliveryCommand(command, acknowledgment));

        verify(processor).applyCancel(any(OrderCancelledEvent.class), eq(command));
    }
}
