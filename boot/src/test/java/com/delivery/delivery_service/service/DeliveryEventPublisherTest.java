package com.delivery.delivery_service.service;

import com.delivery.delivery_service.dto.event.DeliveryCompletedEvent;
import com.delivery.delivery_service.dto.event.DeliveryExceptionReportedEvent;
import com.delivery.delivery_service.dto.event.OfferPersistedEvent;
import com.delivery.delivery_service.dto.event.OfferRetiredEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class DeliveryEventPublisherTest {
    private final OutboxService outbox = mock(OutboxService.class);
    private final DeliveryEventPublisher publisher = new DeliveryEventPublisher(outbox);

    @Test
    void rejectsMissingNonPositiveIdentitiesAndBlankStatusesBeforeOutboxWrites() {
        for (Long identity : Arrays.asList(null, 0L, -1L)) {
            assertInvalidStatus(identity, 2L, 3L, "ASSIGNED", "CREATED");
            assertInvalidStatus(1L, identity, 3L, "ASSIGNED", "CREATED");
            assertInvalidStatus(1L, 2L, identity, "ASSIGNED", "CREATED");
        }
        for (String status : Arrays.asList(null, "", "  ")) {
            assertInvalidStatus(1L, 2L, 3L, status, "CREATED");
            assertInvalidStatus(1L, 2L, 3L, "ASSIGNED", status);
        }
        verifyNoInteractions(outbox);
    }

    @Test
    void rejectsIncompleteExceptionIdentityBeforeOutboxWrites() {
        assertInvalidException(null);
        List<Consumer<DeliveryExceptionReportedEvent>> removals = List.of(
                event -> event.setEventId(null), event -> event.setExceptionId(null),
                event -> event.setDeliveryId(null), event -> event.setOrderId(null));
        for (Consumer<DeliveryExceptionReportedEvent> removal : removals) {
            DeliveryExceptionReportedEvent event = exceptionEvent();
            removal.accept(event);
            assertInvalidException(event);
        }
        verifyNoInteractions(outbox);
    }

    @Test
    void defaultsMissingExceptionTypesAndPreservesExplicitUpdateType() {
        for (String eventType : Arrays.asList(null, "", "  ", "DELIVERY_EXCEPTION_UPDATED")) {
            DeliveryExceptionReportedEvent event = exceptionEvent();
            event.setEventType(eventType);
            publisher.publishDeliveryExceptionUpdated(event);
            verify(outbox).saveEvent(event.getEventId(), "DELIVERY_EXCEPTION", event.getExceptionId().toString(),
                    eventType == null || eventType.isBlank() ? "DELIVERY_EXCEPTION_REPORTED" : eventType,
                    "delivery.exception.reported", "2", event);
        }
    }

    @Test
    void derivesStableDistinctOfferEventIdsFromSourceCommand() {
        UUID commandId = UUID.randomUUID();
        OfferPersistedEvent persisted = new OfferPersistedEvent();
        persisted.setDeliveryId(1L);
        persisted.setOrderId(2L);
        persisted.setSourceCommandEventId(commandId);
        publisher.publishOfferPersisted(persisted);
        UUID persistedId = UUID.nameUUIDFromBytes(("delivery.offer-persisted:" + commandId)
                .getBytes(StandardCharsets.UTF_8));
        verify(outbox).saveEvent(persistedId, "DELIVERY", "1", "OFFER_PERSISTED",
                "delivery.offer-persisted", "2", persisted);

        OfferRetiredEvent retired = new OfferRetiredEvent();
        retired.setDeliveryId(1L);
        retired.setOrderId(2L);
        retired.setSourceCommandEventId(commandId);
        publisher.publishOfferRetired(retired);
        ArgumentCaptor<UUID> retiredId = ArgumentCaptor.forClass(UUID.class);
        verify(outbox).saveEvent(retiredId.capture(), eq("DELIVERY"), eq("1"), eq("OFFER_RETIRED"),
                eq("delivery.offer-retired"), eq("2"), eq(retired));
        assertThat(retiredId.getValue()).isEqualTo(UUID.nameUUIDFromBytes(("delivery.offer-retired:" + commandId)
                .getBytes(StandardCharsets.UTF_8))).isNotEqualTo(persistedId);
    }

    @Test
    void rejectsOffersWithoutSourceCommandAndCompletionWithoutDeliveryId() {
        assertThatThrownBy(() -> publisher.publishOfferPersisted(new OfferPersistedEvent()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("sourceCommandEventId is required");
        assertThatThrownBy(() -> publisher.publishOfferRetired(new OfferRetiredEvent()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("sourceCommandEventId is required");
        assertThatThrownBy(() -> publisher.publishDeliveryCompletedEvent(new DeliveryCompletedEvent()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("deliveryId is required");
        verifyNoInteractions(outbox);
    }

    private void assertInvalidStatus(Long deliveryId, Long orderId, Long userId,
                                     String status, String previousStatus) {
        assertThatThrownBy(() -> publisher.publishDeliveryStatusUpdated(deliveryId, orderId, userId,
                null, status, previousStatus)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("delivery status event identity and statuses are required");
    }

    private void assertInvalidException(DeliveryExceptionReportedEvent event) {
        assertThatThrownBy(() -> publisher.publishDeliveryExceptionReported(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("delivery exception event identity is required");
    }

    private DeliveryExceptionReportedEvent exceptionEvent() {
        return DeliveryExceptionReportedEvent.builder().eventId(UUID.randomUUID())
                .exceptionId(UUID.randomUUID()).deliveryId(1L).orderId(2L).build();
    }
}
