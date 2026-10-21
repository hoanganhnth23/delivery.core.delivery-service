package com.delivery.delivery.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Pure transition policy for the canonical single-delivery lifecycle. */
public final class DeliveryLifecycle {
    private static final Map<DeliveryStatus, Set<DeliveryStatus>> TRANSITIONS = Map.ofEntries(
            Map.entry(DeliveryStatus.PENDING, EnumSet.of(DeliveryStatus.FINDING_SHIPPER, DeliveryStatus.CANCELLED)),
            Map.entry(DeliveryStatus.FINDING_SHIPPER, EnumSet.of(DeliveryStatus.WAIT_SHIPPER_CONFIRM, DeliveryStatus.SHIPPER_NOT_FOUND, DeliveryStatus.CANCELLED)),
            Map.entry(DeliveryStatus.WAIT_SHIPPER_CONFIRM, EnumSet.of(DeliveryStatus.ASSIGNED, DeliveryStatus.FINDING_SHIPPER, DeliveryStatus.CANCELLED)),
            Map.entry(DeliveryStatus.SHIPPER_NOT_FOUND, EnumSet.of(DeliveryStatus.FINDING_SHIPPER, DeliveryStatus.CANCELLED)),
            Map.entry(DeliveryStatus.ASSIGNED, EnumSet.of(DeliveryStatus.PICKED_UP, DeliveryStatus.FINDING_SHIPPER, DeliveryStatus.CANCELLED)),
            Map.entry(DeliveryStatus.PICKED_UP, EnumSet.of(DeliveryStatus.DELIVERING, DeliveryStatus.RETURNING)),
            Map.entry(DeliveryStatus.DELIVERING, EnumSet.of(DeliveryStatus.DELIVERED, DeliveryStatus.RETURNING)),
            Map.entry(DeliveryStatus.DELIVERED, EnumSet.noneOf(DeliveryStatus.class)),
            Map.entry(DeliveryStatus.RETURNING, EnumSet.of(DeliveryStatus.RETURNED)),
            Map.entry(DeliveryStatus.RETURNED, EnumSet.noneOf(DeliveryStatus.class)),
            Map.entry(DeliveryStatus.CANCELLED, EnumSet.noneOf(DeliveryStatus.class)));

    private DeliveryLifecycle() { }

    public static boolean canTransition(DeliveryStatus current, DeliveryStatus next) {
        return current != null && next != null && TRANSITIONS.getOrDefault(current, Set.of()).contains(next);
    }
}
