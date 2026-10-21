package com.delivery.delivery_service.service;

import com.delivery.delivery_service.entity.OutboxEvent;
import com.delivery.delivery_service.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxServiceReplayTest {

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final OutboxService service = new OutboxService(repository, new ObjectMapper());

    @Test
    void exactStableEventReplayDoesNotCreateDuplicateOutboxRow() {
        UUID eventId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        OutboxEvent existing = failureEvent(eventId, "database unavailable");
        when(repository.findByEventId(eventId)).thenReturn(Optional.of(existing));

        assertThat(service.saveEvent(eventId, "ORDER", "101", "DELIVERY_COMMAND_FAILED",
                "delivery.created.failed", "101", failurePayload(eventId, "database unavailable")))
                .isEqualTo(eventId);

        verify(repository, never()).save(any());
    }

    @Test
    void stableEventReplayWithDifferentFailurePayloadFailsClosed() {
        UUID eventId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(repository.findByEventId(eventId))
                .thenReturn(Optional.of(failureEvent(eventId, "database unavailable")));

        assertThatThrownBy(() -> service.saveEvent(eventId, "ORDER", "101",
                "DELIVERY_COMMAND_FAILED", "delivery.created.failed", "101",
                failurePayload(eventId, "validation changed")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contradictory");

        verify(repository, never()).save(any());
    }

    private static Map<String, Object> failurePayload(UUID commandEventId, String reason) {
        return Map.of(
                "commandEventId", commandEventId.toString(),
                "orderId", 101L,
                "success", false,
                "reason", reason);
    }

    private static OutboxEvent failureEvent(UUID eventId, String reason) {
        OutboxEvent event = new OutboxEvent();
        event.setEventId(eventId);
        event.setAggregateType("ORDER");
        event.setAggregateId("101");
        event.setEventType("DELIVERY_COMMAND_FAILED");
        event.setTopic("delivery.created.failed");
        event.setEventKey("101");
        event.setPayload("""
                {"commandEventId":"%s","orderId":101,"success":false,
                 "reason":"%s","eventId":"%s","eventType":"DELIVERY_COMMAND_FAILED",
                 "occurredAt":"2026-07-25T12:00:00"}
                """.formatted(eventId, reason, eventId));
        return event;
    }
}
