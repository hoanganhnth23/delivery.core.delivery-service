package com.delivery.delivery.application.api;

import java.util.UUID;

/** Inbound delivery queries. Results remain adapter-owned response objects. */
public interface DeliveryQueryPort {
    Object currentOffer(DeliveryCommandPort.Actor actor);
    Object currentBatchOffer(DeliveryCommandPort.Actor actor);
    Object batchSnapshot(BatchSnapshotQuery query);
    Object proofAccess(ProofAccessQuery query);
    Object exception(ExceptionQuery query);
    Object deliveryById(DeliveryQuery query);
    Object deliveriesByShipper(ShipperDeliveriesQuery query);
    Object activeDeliveriesByShipper(ShipperDeliveriesQuery query);
    Object deliveryByOrder(DeliveryQuery query);

    record BatchSnapshotQuery(UUID batchId, DeliveryCommandPort.Actor actor) { }
    record ProofAccessQuery(Long deliveryId, UUID proofId, DeliveryCommandPort.Actor actor) { }
    record ExceptionQuery(Long deliveryId, DeliveryCommandPort.Actor actor) { }
    record DeliveryQuery(Long id, DeliveryCommandPort.Actor actor) { }
    record ShipperDeliveriesQuery(Long shipperId, DeliveryCommandPort.Actor actor) { }
}
