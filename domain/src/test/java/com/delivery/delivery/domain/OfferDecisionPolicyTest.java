package com.delivery.delivery.domain;

import com.delivery.delivery.domain.OfferDecisionPolicy.Action;
import com.delivery.delivery.domain.OfferDecisionPolicy.Decision;
import com.delivery.delivery.domain.OfferDecisionPolicy.Offer;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class OfferDecisionPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 8, 0);
    private static final Supplier<Long> NO_ACTIVE = () -> null;

    private static Offer live(Long offered) {
        return new Offer(DeliveryStatus.WAIT_SHIPPER_CONFIRM, null, offered, NOW.plusSeconds(30), null);
    }

    private static OfferDecisionRejected rejected(Runnable call) {
        return assertThrows(OfferDecisionRejected.class, call::run);
    }

    @Test
    void requestRequiresShipperOrderActionAndRejectReason() {
        assertEquals(Action.ACCEPT, OfferDecisionPolicy.requireRequest(true, 1L, "ACCEPT", null));
        assertEquals(Action.REJECT, OfferDecisionPolicy.requireRequest(true, 1L, "REJECT", "busy"));
        OfferDecisionRejected role = rejected(() -> OfferDecisionPolicy.requireRequest(false, 1L, "ACCEPT", null));
        assertEquals(OfferDecisionRejected.Kind.ACCESS_DENIED, role.kind());
        assertEquals("Chỉ shipper mới có thể nhận đơn hàng", role.getMessage());
        assertEquals("Order ID is required",
                rejected(() -> OfferDecisionPolicy.requireRequest(true, null, "ACCEPT", null)).getMessage());
        assertEquals("Action must be ACCEPT or REJECT",
                rejected(() -> OfferDecisionPolicy.requireRequest(true, 1L, "MAYBE", null)).getMessage());
        assertEquals(OfferDecisionRejected.Kind.INVALID_STATUS,
                rejected(() -> OfferDecisionPolicy.requireRequest(true, 1L, null, null)).kind());
        assertEquals("Reject reason is required when rejecting delivery",
                rejected(() -> OfferDecisionPolicy.requireRequest(true, 1L, "REJECT", " ")).getMessage());
        rejected(() -> OfferDecisionPolicy.requireRequest(true, 1L, "REJECT", null));
    }

    @Test
    void offeredShipperDecidesLiveOffer() {
        assertEquals(Decision.ACCEPT, OfferDecisionPolicy.decide(Action.ACCEPT, 9L, live(9L), null, NOW, NO_ACTIVE));
        assertEquals(Decision.REJECT, OfferDecisionPolicy.decide(Action.REJECT, 9L, live(9L), "busy", NOW,
                () -> fail("active guard applies only to ACCEPT")));
    }

    @Test
    void exactReplaysAreNoOps() {
        Offer assigned = new Offer(DeliveryStatus.ASSIGNED, 9L, 9L, null, null);
        assertEquals(Decision.ACCEPT_REPLAY, OfferDecisionPolicy.decide(Action.ACCEPT, 9L, assigned, null, NOW, NO_ACTIVE));
        Offer rejectedOffer = new Offer(DeliveryStatus.FINDING_SHIPPER, null, 9L, null, "busy");
        assertEquals(Decision.REJECT_REPLAY,
                OfferDecisionPolicy.decide(Action.REJECT, 9L, rejectedOffer, "busy", NOW, NO_ACTIVE));
        assertTrue(OfferDecisionPolicy.isRejectReplay(rejectedOffer, 9L, "busy"));
        assertFalse(OfferDecisionPolicy.isRejectReplay(rejectedOffer, 9L, "other"));
        assertFalse(OfferDecisionPolicy.isRejectReplay(rejectedOffer, null, "busy"));
        assertFalse(OfferDecisionPolicy.isRejectReplay(rejectedOffer, 8L, "busy"));
        assertFalse(OfferDecisionPolicy.isRejectReplay(
                new Offer(DeliveryStatus.FINDING_SHIPPER, 9L, 9L, null, "busy"), 9L, "busy"));
        assertFalse(OfferDecisionPolicy.isRejectReplay(
                new Offer(DeliveryStatus.FINDING_SHIPPER, null, 9L, NOW, "busy"), 9L, "busy"));
        assertFalse(OfferDecisionPolicy.isRejectReplay(
                new Offer(DeliveryStatus.ASSIGNED, null, 9L, null, "busy"), 9L, "busy"));
    }

    @Test
    void statusOfferedShipperAndExpiryAreEnforcedInOrder() {
        assertEquals("Đơn hàng không ở trạng thái chờ shipper xác nhận", rejected(() -> OfferDecisionPolicy.decide(
                Action.ACCEPT, 9L, new Offer(DeliveryStatus.FINDING_SHIPPER, null, 9L, null, null), null, NOW,
                NO_ACTIVE)).getMessage());
        assertEquals(OfferDecisionRejected.Kind.INVALID_STATUS, rejected(() -> OfferDecisionPolicy.decide(
                Action.ACCEPT, 9L, new Offer(DeliveryStatus.ASSIGNED, 8L, 9L, null, null), null, NOW, NO_ACTIVE)).kind());
        OfferDecisionRejected other = rejected(() -> OfferDecisionPolicy.decide(Action.ACCEPT, 9L, live(8L), null,
                NOW, NO_ACTIVE));
        assertEquals(OfferDecisionRejected.Kind.ACCESS_DENIED, other.kind());
        assertEquals("Đơn hàng này không được offer cho shipper hiện tại", other.getMessage());
        assertEquals(OfferDecisionRejected.Kind.ACCESS_DENIED,
                rejected(() -> OfferDecisionPolicy.decide(Action.REJECT, 9L, live(null), "x", NOW, NO_ACTIVE)).kind());
        assertEquals("Offer nhận đơn đã hết hạn", rejected(() -> OfferDecisionPolicy.decide(Action.ACCEPT, 9L,
                new Offer(DeliveryStatus.WAIT_SHIPPER_CONFIRM, null, 9L, NOW, null), null, NOW, NO_ACTIVE)).getMessage());
        rejected(() -> OfferDecisionPolicy.decide(Action.ACCEPT, 9L,
                new Offer(DeliveryStatus.WAIT_SHIPPER_CONFIRM, null, 9L, null, null), null, NOW, NO_ACTIVE));
    }

    @Test
    void shipperWithActiveDeliveryCannotAcceptAndAssignedElsewhereFails() {
        assertEquals("Bạn đang có đơn hàng đang xử lý (Delivery #77). Hãy hoàn thành đơn hiện tại trước khi nhận đơn mới!",
                rejected(() -> OfferDecisionPolicy.decide(Action.ACCEPT, 9L, live(9L), null, NOW, () -> 77L)).getMessage());
        Offer assignedElsewhere = new Offer(DeliveryStatus.WAIT_SHIPPER_CONFIRM, 8L, 9L, NOW.plusSeconds(30), null);
        assertEquals("Đơn hàng đã được giao cho shipper khác", rejected(() -> OfferDecisionPolicy.decide(
                Action.REJECT, 9L, assignedElsewhere, "x", NOW, NO_ACTIVE)).getMessage());
        assertEquals(Decision.REJECT, OfferDecisionPolicy.decide(Action.REJECT, 9L,
                new Offer(DeliveryStatus.WAIT_SHIPPER_CONFIRM, 9L, 9L, NOW.plusSeconds(30), null), "x", NOW, NO_ACTIVE));
    }
}
