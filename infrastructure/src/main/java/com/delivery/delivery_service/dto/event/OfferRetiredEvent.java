package com.delivery.delivery_service.dto.event;

import lombok.Data;

import java.util.UUID;

/** Confirmation that Delivery fenced one expired/abandoned matching generation. */
@Data
public class OfferRetiredEvent {
    private UUID eventId;
    private UUID sourceCommandEventId;
    private Long orderId;
    private Long deliveryId;
    private String matchingSessionId;
    private String outcome;
    private Long shipperId;
}
