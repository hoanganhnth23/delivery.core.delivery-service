package com.delivery.delivery_service.service;

import com.delivery.delivery.domain.BatchRoutePolicy;
import com.delivery.delivery_service.dto.event.ShipperFoundEvent;
import com.delivery.delivery_service.entity.DeliveryBatchItem;
import java.util.List;
import static com.delivery.delivery_service.service.DeliveryPolicyAdapter.policy;

/** Converts event/database rows to framework-free route values. */
public final class DeliveryBatchRouteValidator {
    private DeliveryBatchRouteValidator() {}
    public static void validate(List<ShipperFoundEvent.BatchItem> items) {
        policy(() -> BatchRoutePolicy.validate(items == null ? null : items.stream().map(item -> item == null ? null
                : new BatchRoutePolicy.Stop(item.getDeliveryId(), item.getOrderId(),
                        item.getPickupSequence(), item.getDropoffSequence())).toList()));
    }
    public static void validatePersisted(List<DeliveryBatchItem> items) {
        policy(() -> BatchRoutePolicy.validatePersisted(items == null ? null : items.stream().map(item -> item == null ? null
                : new BatchRoutePolicy.Stop(item.getDeliveryId(), null,
                        item.getPickupSequence(), item.getDropoffSequence())).toList()));
    }
}
