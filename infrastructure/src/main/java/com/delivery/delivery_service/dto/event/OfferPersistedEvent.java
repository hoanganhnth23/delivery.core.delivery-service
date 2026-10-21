package com.delivery.delivery_service.dto.event;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;
import com.delivery.identity.contracts.SimulationContext;

/** Delivery-owned confirmation that one shipper offer committed locally. */
@Data
public class OfferPersistedEvent {
    private UUID eventId;
    private UUID sourceCommandEventId;
    private Long orderId;
    private Long deliveryId;
    private String matchingSessionId;
    private Long offeredShipperId;
    private LocalDateTime offerExpiresAt;
    private String status = "WAIT_SHIPPER_CONFIRM";
    private SimulationContext simulationContext;
}
