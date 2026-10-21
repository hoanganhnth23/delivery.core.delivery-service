package com.delivery.delivery.domain;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.function.Supplier;

import static com.delivery.delivery.domain.OfferDecisionRejected.Kind.ACCESS_DENIED;
import static com.delivery.delivery.domain.OfferDecisionRejected.Kind.INVALID_STATUS;

/**
 * Shipper ACCEPT/REJECT of the single active offer (delivery-matching.md
 * C2–C8): only the offered shipper may decide while the offer is live, a
 * shipper holds at most one active delivery, and exact replays are no-ops.
 */
public final class OfferDecisionPolicy {

    public static final String ACCEPT = "ACCEPT";
    public static final String REJECT = "REJECT";

    public enum Action { ACCEPT, REJECT }

    public enum Decision { ACCEPT_REPLAY, REJECT_REPLAY, ACCEPT, REJECT }

    /** The delivery's offer state as persisted. */
    public record Offer(DeliveryStatus status, Long assignedShipperId, Long offeredShipperId,
                        LocalDateTime offerExpiresAt, String rejectReason) {
    }

    private OfferDecisionPolicy() {
    }

    public static Action requireRequest(boolean shipperRole, Long orderId, String action, String rejectReason) {
        if (!shipperRole) {
            throw new OfferDecisionRejected(ACCESS_DENIED, "Chỉ shipper mới có thể nhận đơn hàng");
        }
        if (orderId == null) {
            throw new OfferDecisionRejected(INVALID_STATUS, "Order ID is required");
        }
        if (action == null || (!ACCEPT.equals(action) && !REJECT.equals(action))) {
            throw new OfferDecisionRejected(INVALID_STATUS, "Action must be ACCEPT or REJECT");
        }
        if (REJECT.equals(action) && (rejectReason == null || rejectReason.trim().isEmpty())) {
            throw new OfferDecisionRejected(INVALID_STATUS, "Reject reason is required when rejecting delivery");
        }
        return ACCEPT.equals(action) ? Action.ACCEPT : Action.REJECT;
    }

    /**
     * @param activeDeliveryId evaluated only for a live ACCEPT; the id of another
     *                         active delivery of this shipper, or null
     */
    public static Decision decide(Action action, Long shipperId, Offer offer, String rejectReason,
                                  LocalDateTime now, Supplier<Long> activeDeliveryId) {
        if (action == Action.ACCEPT && offer.status() == DeliveryStatus.ASSIGNED
                && shipperId.equals(offer.assignedShipperId())) {
            return Decision.ACCEPT_REPLAY;
        }
        if (action == Action.REJECT && isRejectReplay(offer, shipperId, rejectReason)) {
            return Decision.REJECT_REPLAY;
        }
        if (offer.status() != DeliveryStatus.WAIT_SHIPPER_CONFIRM) {
            throw new OfferDecisionRejected(INVALID_STATUS, "Đơn hàng không ở trạng thái chờ shipper xác nhận");
        }
        if (offer.offeredShipperId() == null || !offer.offeredShipperId().equals(shipperId)) {
            throw new OfferDecisionRejected(ACCESS_DENIED, "Đơn hàng này không được offer cho shipper hiện tại");
        }
        if (offer.offerExpiresAt() == null || !offer.offerExpiresAt().isAfter(now)) {
            throw new OfferDecisionRejected(INVALID_STATUS, "Offer nhận đơn đã hết hạn");
        }
        if (action == Action.ACCEPT) {
            Long active = activeDeliveryId.get();
            if (active != null) {
                throw new OfferDecisionRejected(INVALID_STATUS, "Bạn đang có đơn hàng đang xử lý (Delivery #"
                        + active + "). Hãy hoàn thành đơn hiện tại trước khi nhận đơn mới!");
            }
        }
        if (offer.assignedShipperId() != null && !offer.assignedShipperId().equals(shipperId)) {
            throw new OfferDecisionRejected(INVALID_STATUS, "Đơn hàng đã được giao cho shipper khác");
        }
        return action == Action.ACCEPT ? Decision.ACCEPT : Decision.REJECT;
    }

    /** The same shipper's rejection was already applied and the offer cleared. */
    public static boolean isRejectReplay(Offer offer, Long shipperId, String rejectReason) {
        return offer.status() == DeliveryStatus.FINDING_SHIPPER
                && offer.assignedShipperId() == null
                && offer.offerExpiresAt() == null
                && shipperId != null
                && shipperId.equals(offer.offeredShipperId())
                && Objects.equals(offer.rejectReason(), rejectReason);
    }
}
