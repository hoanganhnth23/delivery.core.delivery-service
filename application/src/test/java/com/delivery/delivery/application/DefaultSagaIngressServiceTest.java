package com.delivery.delivery.application;

import com.delivery.delivery.application.api.ClockPort;
import com.delivery.delivery.application.api.DeliveryStoragePort;
import com.delivery.delivery.application.api.MatchPort;
import com.delivery.delivery.application.api.SagaIngressPort;
import com.delivery.delivery.domain.DeliveryStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultSagaIngressServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");
    private final RecordingStorage storage = new RecordingStorage();
    private final RecordingMatching matching = new RecordingMatching();
    private final SagaIngressPort service = new DefaultSagaIngressService(storage, matching, () -> NOW);

    @Test
    void createPersistsBeforeStartingMatching() {
        storage.nextId = 41L;

        service.createDelivery(new SagaIngressPort.CreateDeliveryCommand(
                9L, 7L, 5L, new BigDecimal("12000.00")));

        assertThat(storage.saved).hasSize(2);
        assertThat(storage.saved.get(0).status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(storage.saved.get(1).status()).isEqualTo(DeliveryStatus.FINDING_SHIPPER);
        assertThat(matching.findCommands).hasSize(1);
        assertThat(matching.findCommands.get(0).deliveryId()).isEqualTo(41L);
    }

    @Test
    void updateAllowsLifecycleTransitionAndRepeatedCommandIsNoOp() {
        storage.rows.put(41L, snapshot(DeliveryStatus.ASSIGNED));

        service.updateStatus(new SagaIngressPort.UpdateDeliveryStatusCommand(
                41L, DeliveryStatus.PICKED_UP, "command-1"));
        service.updateStatus(new SagaIngressPort.UpdateDeliveryStatusCommand(
                41L, DeliveryStatus.PICKED_UP, "command-1"));

        assertThat(storage.saved).hasSize(1);
        assertThat(storage.saved.get(0).status()).isEqualTo(DeliveryStatus.PICKED_UP);
    }

    @Test
    void invalidTransitionDoesNotWrite() {
        storage.rows.put(41L, snapshot(DeliveryStatus.DELIVERED));

        assertThatThrownBy(() -> service.updateStatus(new SagaIngressPort.UpdateDeliveryStatusCommand(
                41L, DeliveryStatus.CANCELLED, "command-1")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(storage.saved).isEmpty();
    }

    @Test
    void cancelPersistsBeforeStoppingMatchingAndIsIdempotent() {
        storage.rows.put(41L, snapshot(DeliveryStatus.FINDING_SHIPPER));

        service.cancel(new SagaIngressPort.CancelDeliveryCommand(41L, 9L, "customer-request", "cancel-1"));
        service.cancel(new SagaIngressPort.CancelDeliveryCommand(41L, 9L, "customer-request", "cancel-1"));

        assertThat(storage.saved).hasSize(1);
        assertThat(storage.saved.get(0).status()).isEqualTo(DeliveryStatus.CANCELLED);
        assertThat(matching.stopCommands).hasSize(1);
    }

    @Test
    void invalidInputNeverReachesPorts() {
        assertThatThrownBy(() -> service.createDelivery(new SagaIngressPort.CreateDeliveryCommand(
                0L, 7L, 5L, BigDecimal.ZERO))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateStatus(new SagaIngressPort.UpdateDeliveryStatusCommand(
                41L, null, "command-1"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(storage.saved).isEmpty();
        assertThat(matching.findCommands).isEmpty();
    }

    @Test
    void rejectsNullAndNegativeFeesAndMissingPersistenceIdentity() {
        assertThatThrownBy(() -> service.createDelivery(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.createDelivery(new SagaIngressPort.CreateDeliveryCommand(null, 7L, 5L, BigDecimal.ZERO)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createDelivery(new SagaIngressPort.CreateDeliveryCommand(9L, 7L, 5L, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createDelivery(new SagaIngressPort.CreateDeliveryCommand(9L, 7L, 5L, new BigDecimal("-1"))))
                .isInstanceOf(IllegalArgumentException.class);
        storage.returnNull = true;
        assertThatThrownBy(() -> service.createDelivery(new SagaIngressPort.CreateDeliveryCommand(9L, 7L, 5L, BigDecimal.ZERO)))
                .isInstanceOf(IllegalStateException.class);
        storage.returnNull = false; storage.returnWithoutId = true;
        assertThatThrownBy(() -> service.createDelivery(new SagaIngressPort.CreateDeliveryCommand(9L, 7L, 5L, BigDecimal.ZERO)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsInvalidCancellationAndNullConstructionDependencies() {
        storage.rows.put(41L, snapshot(DeliveryStatus.DELIVERED));
        assertThatThrownBy(() -> service.cancel(new SagaIngressPort.CancelDeliveryCommand(41L, 9L, "x", "c")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.updateStatus(new SagaIngressPort.UpdateDeliveryStatusCommand(null, DeliveryStatus.PICKED_UP, "c")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DefaultSagaIngressService(null, matching, () -> NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DefaultSagaIngressService(storage, null, () -> NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DefaultSagaIngressService(storage, matching, null))
                .isInstanceOf(NullPointerException.class);
    }

    private DeliveryStoragePort.DeliverySnapshot snapshot(DeliveryStatus status) {
        return new DeliveryStoragePort.DeliverySnapshot(41L, 9L, 7L, 5L, null, status,
                BigDecimal.ZERO, NOW);
    }

    private final class RecordingStorage implements DeliveryStoragePort {
        private final Map<Long, DeliverySnapshot> rows = new HashMap<>();
        private final List<DeliverySnapshot> saved = new ArrayList<>();
        private long nextId = 1L;
        private boolean returnNull;
        private boolean returnWithoutId;

        @Override
        public Optional<DeliverySnapshot> findById(Long deliveryId) {
            return Optional.ofNullable(rows.get(deliveryId));
        }

        @Override
        public DeliverySnapshot save(DeliverySnapshot delivery) {
            if (returnNull) return null;
            DeliverySnapshot persisted = delivery.deliveryId() == null
                    ? new DeliverySnapshot(returnWithoutId ? null : nextId, delivery.orderId(), delivery.userId(), delivery.restaurantId(),
                    delivery.shipperId(), delivery.status(), delivery.shippingFee(), delivery.updatedAt())
                    : delivery;
            if (persisted.deliveryId() == null) return persisted;
            nextId = persisted.deliveryId() + 1;
            rows.put(persisted.deliveryId(), persisted);
            saved.add(persisted);
            return persisted;
        }
    }

    private static final class RecordingMatching implements MatchPort {
        private final List<FindShipperCommand> findCommands = new ArrayList<>();
        private final List<StopMatchingCommand> stopCommands = new ArrayList<>();

        @Override
        public void findShipper(FindShipperCommand command) { findCommands.add(command); }

        @Override
        public void stopMatching(StopMatchingCommand command) { stopCommands.add(command); }
    }
}
