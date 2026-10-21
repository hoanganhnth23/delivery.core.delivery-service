package com.delivery.delivery_service.dto.event;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/** Saga command that clears exactly the offer generation that timed out. */
@Getter
@Setter
public class ExpireShipperOfferCommand {
    private UUID eventId;
    private Long orderId;
    private Long deliveryId;
    private Long timedOutShipperId;
    private LocalDateTime expectedOfferExpiresAt;
    /** Saga-owned generation; optional only while draining legacy commands. */
    private String matchingSessionId;
}
