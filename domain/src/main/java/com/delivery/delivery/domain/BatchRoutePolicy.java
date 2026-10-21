package com.delivery.delivery.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Authoritative global stop numbering, preserving event and persisted validation contracts. */
public final class BatchRoutePolicy {
    private BatchRoutePolicy() {}
    public record Stop(Long deliveryId, Long orderId, Integer pickupSequence, Integer dropoffSequence) {}

    public static void validate(List<Stop> items) {
        if (items == null || items.isEmpty() || items.size() > 3) {
            throw new OfferDecisionRejected(OfferDecisionRejected.Kind.INVALID_STATUS, "Batch item count is invalid");
        }
        Set<Long> deliveries = new HashSet<>();
        Set<Long> orders = new HashSet<>();
        Set<Integer> pickups = new HashSet<>();
        Set<Integer> dropoffs = new HashSet<>();
        int stopCount = items.size() * 2;
        for (Stop item : items) {
            if (item == null || item.deliveryId() == null || item.orderId() == null
                    || item.pickupSequence() == null || item.dropoffSequence() == null
                    || item.pickupSequence() < 0 || item.dropoffSequence() < 0
                    || item.pickupSequence() >= stopCount || item.dropoffSequence() >= stopCount
                    || item.pickupSequence() >= item.dropoffSequence()
                    || !deliveries.add(item.deliveryId())
                    || !orders.add(item.orderId())
                    || !pickups.add(item.pickupSequence())
                    || !dropoffs.add(item.dropoffSequence())) {
                throw new OfferDecisionRejected(OfferDecisionRejected.Kind.INVALID_STATUS, "Batch delivery IDs and stop sequences are invalid");
            }
        }
        validateContiguous(pickups, dropoffs, stopCount);
    }

    /** Validates persisted batch items after loading them from the database. */
    public static void validatePersisted(List<Stop> items) {
        if (items == null || items.isEmpty() || items.size() > 3) {
            throw new OfferDecisionRejected(OfferDecisionRejected.Kind.INVALID_STATUS, "Batch item count is invalid");
        }
        Set<Long> deliveries = new HashSet<>();
        Set<Integer> pickups = new HashSet<>();
        Set<Integer> dropoffs = new HashSet<>();
        int stopCount = items.size() * 2;
        for (Stop item : items) {
            if (item == null || item.deliveryId() == null
                    || item.pickupSequence() < 0 || item.dropoffSequence() < 0
                    || item.pickupSequence() >= stopCount || item.dropoffSequence() >= stopCount
                    || item.pickupSequence() >= item.dropoffSequence()
                    || !deliveries.add(item.deliveryId())
                    || !pickups.add(item.pickupSequence())
                    || !dropoffs.add(item.dropoffSequence())) {
                throw new OfferDecisionRejected(OfferDecisionRejected.Kind.INVALID_STATUS, "Persisted batch item sequences are invalid");
            }
        }
        validateContiguous(pickups, dropoffs, stopCount);
    }

    static void validateContiguous(Set<Integer> pickups, Set<Integer> dropoffs, int stopCount) {
        Set<Integer> all = new HashSet<>(pickups);
        all.addAll(dropoffs);
        if (pickups.size() * 2 != stopCount || dropoffs.size() * 2 != stopCount
                || all.size() != stopCount) {
            throw new OfferDecisionRejected(OfferDecisionRejected.Kind.INVALID_STATUS, "Batch route sequences must cover every global stop exactly once");
        }
        for (int sequence = 0; sequence < stopCount; sequence++) {
            if (!all.contains(sequence)) {
                throw new OfferDecisionRejected(OfferDecisionRejected.Kind.INVALID_STATUS, "Batch route sequences must be contiguous");
            }
        }
    }
}
