package com.delivery.delivery.application.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Outbound boundary used by delivery orchestration to start/stop matching. */
public interface MatchPort {
    void findShipper(FindShipperCommand command);
    void stopMatching(StopMatchingCommand command);

    record FindShipperCommand(Long deliveryId, Long orderId, UUID matchingSessionId,
                              List<Long> excludedShipperIds, Instant requestedAt) { }
    record StopMatchingCommand(Long deliveryId, UUID matchingSessionId, String reason) { }
}
