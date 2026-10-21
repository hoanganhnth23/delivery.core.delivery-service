package com.delivery.delivery_service.dto.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.delivery.identity.contracts.SimulationContext;

/**
 * Canonical payload for delivery.shipper-accepted.
 *
 * The outbox supplies the stable eventId and occurredAt. Saga and Order only
 * need the aggregate and actor identities plus the optional shipper note.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShipperAcceptedEvent {
    private Long orderId;
    private Long deliveryId;
    private Long shipperId;
    private String notes;
    private SimulationContext simulationContext;
}
