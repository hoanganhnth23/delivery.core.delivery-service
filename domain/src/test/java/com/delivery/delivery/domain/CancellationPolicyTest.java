package com.delivery.delivery.domain;

import com.delivery.delivery.domain.CancellationPolicy.NotFound;
import com.delivery.delivery.domain.CancellationPolicy.OrderCancel;
import com.delivery.delivery.domain.CancellationPolicy.ShipperCancel;
import org.junit.jupiter.api.Test;

import static com.delivery.delivery.domain.DeliveryStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class CancellationPolicyTest {

    @Test
    void shipperCancelRequestNeedsShipperRoleAndOrder() {
        CancellationPolicy.requireShipperCancelRequest(true, 1L);
        OfferDecisionRejected role = assertThrows(OfferDecisionRejected.class,
                () -> CancellationPolicy.requireShipperCancelRequest(false, 1L));
        assertEquals(OfferDecisionRejected.Kind.ACCESS_DENIED, role.kind());
        assertEquals("Chỉ shipper mới có thể huỷ đơn đã nhận", role.getMessage());
        assertEquals("Order ID is required", assertThrows(OfferDecisionRejected.class,
                () -> CancellationPolicy.requireShipperCancelRequest(true, null)).getMessage());
        assertEquals("busy", CancellationPolicy.shipperCancelReason("busy"));
        assertEquals("Shipper huỷ sau khi nhận", CancellationPolicy.shipperCancelReason(" "));
        assertEquals("Shipper huỷ sau khi nhận", CancellationPolicy.shipperCancelReason(null));
    }

    @Test
    void assignedShipperMayCancelOnlyBeforePickup() {
        assertEquals(ShipperCancel.REPLAY, CancellationPolicy.onShipperCancel(FINDING_SHIPPER, false, null, 9L, true));
        assertEquals(ShipperCancel.CANCEL_BATCH, CancellationPolicy.onShipperCancel(ASSIGNED, true, 9L, 9L, false));
        assertEquals(ShipperCancel.RESET_TO_FINDING, CancellationPolicy.onShipperCancel(ASSIGNED, false, 9L, 9L, false));
        OfferDecisionRejected other = assertThrows(OfferDecisionRejected.class,
                () -> CancellationPolicy.onShipperCancel(ASSIGNED, false, 8L, 9L, false));
        assertEquals(OfferDecisionRejected.Kind.ACCESS_DENIED, other.kind());
        assertThrows(OfferDecisionRejected.class, () -> CancellationPolicy.onShipperCancel(ASSIGNED, false, null, 9L, false));
        assertEquals("Chỉ có thể huỷ đơn khi chưa lấy hàng (trạng thái ASSIGNED). Hiện tại: PICKED_UP",
                assertThrows(OfferDecisionRejected.class,
                        () -> CancellationPolicy.onShipperCancel(PICKED_UP, true, 9L, 9L, false)).getMessage());
    }

    @Test
    void orderCancellationIsHonoredUntilPickup() {
        assertEquals(OrderCancel.ALREADY_CANCELLED, CancellationPolicy.onOrderCancelled(5, CANCELLED));
        for (DeliveryStatus status : new DeliveryStatus[] {PENDING, FINDING_SHIPPER, WAIT_SHIPPER_CONFIRM,
                SHIPPER_NOT_FOUND, ASSIGNED}) {
            assertEquals(OrderCancel.CANCEL, CancellationPolicy.onOrderCancelled(5, status));
        }
        assertEquals("Cannot cancel delivery 5 in status PICKED_UP", assertThrows(OfferDecisionRejected.class,
                () -> CancellationPolicy.onOrderCancelled(5, PICKED_UP)).getMessage());
        assertThrows(OfferDecisionRejected.class, () -> CancellationPolicy.onOrderCancelled(5, DELIVERED));
    }

    @Test
    void shipperNotFoundAppliesOnlyWhileFinding() {
        assertEquals(NotFound.ALREADY_APPLIED, CancellationPolicy.onShipperNotFound(SHIPPER_NOT_FOUND));
        assertEquals(NotFound.APPLY, CancellationPolicy.onShipperNotFound(FINDING_SHIPPER));
        for (DeliveryStatus stronger : new DeliveryStatus[] {ASSIGNED, PICKED_UP, DELIVERING, DELIVERED, CANCELLED}) {
            assertEquals(NotFound.IGNORE_STALE, CancellationPolicy.onShipperNotFound(stronger));
        }
        assertEquals("Contradictory shipper-not-found event in status WAIT_SHIPPER_CONFIRM",
                assertThrows(OfferDecisionRejected.class,
                        () -> CancellationPolicy.onShipperNotFound(WAIT_SHIPPER_CONFIRM)).getMessage());
    }
}
