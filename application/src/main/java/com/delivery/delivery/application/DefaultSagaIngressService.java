package com.delivery.delivery.application;

import com.delivery.delivery.application.api.ClockPort;
import com.delivery.delivery.application.api.DeliveryStoragePort;
import com.delivery.delivery.application.api.MatchPort;
import com.delivery.delivery.application.api.SagaIngressPort;
import com.delivery.delivery.domain.DeliveryLifecycle;
import com.delivery.delivery.domain.DeliveryStatus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Framework-free delivery command orchestration. Adapters own transactions,
 * retries, wire formats, and persistence details; this class owns lifecycle
 * decisions and the ordering of the matching side effect.
 */
public final class DefaultSagaIngressService implements SagaIngressPort {
    private final DeliveryStoragePort storage;
    private final MatchPort matching;
    private final ClockPort clock;

    public DefaultSagaIngressService(DeliveryStoragePort storage, MatchPort matching, ClockPort clock) {
        this.storage = require(storage, "storage");
        this.matching = require(matching, "matching");
        this.clock = require(clock, "clock");
    }

    @Override
    public void createDelivery(CreateDeliveryCommand command) {
        require(command, "command");
        requirePositive(command.orderId(), "orderId");
        requirePositive(command.userId(), "userId");
        requirePositive(command.restaurantId(), "restaurantId");
        if (command.shippingFee() == null || command.shippingFee().signum() < 0) {
            throw new IllegalArgumentException("shippingFee must be non-negative");
        }

        Instant now = clock.now();
        DeliveryStoragePort.DeliverySnapshot snapshot = new DeliveryStoragePort.DeliverySnapshot(
                null, command.orderId(), command.userId(), command.restaurantId(), null,
                DeliveryStatus.PENDING, command.shippingFee(), now);
        DeliveryStoragePort.DeliverySnapshot saved = storage.save(snapshot);
        if (saved == null || saved.deliveryId() == null) {
            throw new IllegalStateException("storage must return a persisted delivery id");
        }

        DeliveryStoragePort.DeliverySnapshot matchingSnapshot = copyStatus(saved, DeliveryStatus.FINDING_SHIPPER, now);
        storage.save(matchingSnapshot);
        matching.findShipper(new MatchPort.FindShipperCommand(
                saved.deliveryId(), saved.orderId(), sessionFor(saved.deliveryId()), List.of(), now));
    }

    @Override
    public void updateStatus(UpdateDeliveryStatusCommand command) {
        require(command, "command");
        if (command.status() == null) throw new IllegalArgumentException("status is required");
        DeliveryStoragePort.DeliverySnapshot current = find(command.deliveryId());
        if (current.status() == command.status()) {
            return;
        }
        if (!DeliveryLifecycle.canTransition(current.status(), command.status())) {
            throw new IllegalStateException("Invalid delivery transition: "
                    + current.status() + " -> " + command.status());
        }
        storage.save(copyStatus(current, command.status(), clock.now()));
    }

    @Override
    public void cancel(CancelDeliveryCommand command) {
        require(command, "command");
        DeliveryStoragePort.DeliverySnapshot current = find(command.deliveryId());
        if (current.status() == DeliveryStatus.CANCELLED) {
            return;
        }
        if (!DeliveryLifecycle.canTransition(current.status(), DeliveryStatus.CANCELLED)) {
            throw new IllegalStateException("Cannot cancel delivery in status " + current.status());
        }
        Instant now = clock.now();
        storage.save(copyStatus(current, DeliveryStatus.CANCELLED, now));
        matching.stopMatching(new MatchPort.StopMatchingCommand(
                current.deliveryId(), sessionFor(current.deliveryId()), command.reason()));
    }

    private DeliveryStoragePort.DeliverySnapshot find(Long id) {
        requirePositive(id, "deliveryId");
        return storage.findById(id).orElseThrow(
                () -> new IllegalArgumentException("Delivery not found: " + id));
    }

    private DeliveryStoragePort.DeliverySnapshot copyStatus(
            DeliveryStoragePort.DeliverySnapshot source, DeliveryStatus status, Instant updatedAt) {
        return new DeliveryStoragePort.DeliverySnapshot(source.deliveryId(), source.orderId(), source.userId(),
                source.restaurantId(), source.shipperId(), status, source.shippingFee(), updatedAt);
    }

    private UUID sessionFor(Long deliveryId) {
        return UUID.nameUUIDFromBytes(("delivery:" + deliveryId).getBytes(StandardCharsets.UTF_8));
    }

    private static void requirePositive(Long value, String name) {
        if (value == null || value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new NullPointerException(name + " is required");
        return value;
    }
}
