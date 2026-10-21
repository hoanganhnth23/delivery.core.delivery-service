package com.delivery.delivery.domain;

import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static com.delivery.delivery.domain.DeliveryStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class ShipperProgressPolicyTest {

    private static final Function<DeliveryStatus, String> NAME = Enum::name;

    @Test
    void onlyAssignedShipperMayAdvance() {
        ShipperProgressPolicy.requireAssignedShipper(true, 9L, 9L);
        for (Runnable denied : new Runnable[] {
                () -> ShipperProgressPolicy.requireAssignedShipper(false, 9L, 9L),
                () -> ShipperProgressPolicy.requireAssignedShipper(true, null, 9L),
                () -> ShipperProgressPolicy.requireAssignedShipper(true, 8L, 9L)}) {
            OfferDecisionRejected rejected = assertThrows(OfferDecisionRejected.class, denied::run);
            assertEquals(OfferDecisionRejected.Kind.ACCESS_DENIED, rejected.kind());
        }
    }

    @Test
    void shipperTargetsAreLimitedToPickupDeliveringDelivered() {
        ShipperProgressPolicy.requireShipperTarget(PICKED_UP);
        ShipperProgressPolicy.requireShipperTarget(DELIVERING);
        ShipperProgressPolicy.requireShipperTarget(DELIVERED);
        assertEquals("Shipper chỉ có thể cập nhật PICKED_UP, DELIVERING hoặc DELIVERED",
                assertThrows(OfferDecisionRejected.class,
                        () -> ShipperProgressPolicy.requireShipperTarget(CANCELLED)).getMessage());
        assertThrows(OfferDecisionRejected.class, () -> ShipperProgressPolicy.requireShipperTarget(null));
    }

    @Test
    void progressIsStrictAndReplayIsNoOp() {
        assertEquals(ShipperProgressPolicy.Progress.ADVANCE, ShipperProgressPolicy.decide(ASSIGNED, PICKED_UP, NAME));
        assertEquals(ShipperProgressPolicy.Progress.ADVANCE, ShipperProgressPolicy.decide(PICKED_UP, DELIVERING, NAME));
        assertEquals(ShipperProgressPolicy.Progress.ADVANCE, ShipperProgressPolicy.decide(DELIVERING, DELIVERED, NAME));
        assertEquals(ShipperProgressPolicy.Progress.REPLAY, ShipperProgressPolicy.decide(DELIVERED, DELIVERED, NAME));
        assertEquals("Không thể chuyển từ trạng thái ASSIGNED sang DELIVERED", assertThrows(OfferDecisionRejected.class,
                () -> ShipperProgressPolicy.decide(ASSIGNED, DELIVERED, NAME)).getMessage());
        assertThrows(OfferDecisionRejected.class, () -> ShipperProgressPolicy.decide(CANCELLED, PICKED_UP, NAME));
    }

    @Test
    void deliveredIsGatedAndReleasesShipperOnlyWhenBatchCompletes() {
        assertTrue(ShipperProgressPolicy.requiresProofGate(DELIVERED));
        assertFalse(ShipperProgressPolicy.requiresProofGate(PICKED_UP));
        assertTrue(ShipperProgressPolicy.releasesShipper(DELIVERED, true, true));
        assertFalse(ShipperProgressPolicy.releasesShipper(DELIVERED, true, false));
        assertFalse(ShipperProgressPolicy.releasesShipper(DELIVERED, false, true));
        assertFalse(ShipperProgressPolicy.releasesShipper(DELIVERING, true, true));
    }
}
