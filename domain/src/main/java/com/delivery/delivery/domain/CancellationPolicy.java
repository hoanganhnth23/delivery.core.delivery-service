package com.delivery.delivery.domain;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

import static com.delivery.delivery.domain.OfferDecisionRejected.Kind.ACCESS_DENIED;
import static com.delivery.delivery.domain.OfferDecisionRejected.Kind.INVALID_STATUS;

/**
 * Cancellation paths: a shipper may drop an accepted delivery only before
 * pickup (the case rematches), an order cancellation is honored until
 * pickup and refused afterwards, and shipper-not-found applies only while
 * finding a shipper.
 */
public final class CancellationPolicy {

    public static final String DEFAULT_SHIPPER_CANCEL_REASON = "Shipper huỷ sau khi nhận";

    private static final Set<DeliveryStatus> ORDER_CANCELLABLE = EnumSet.of(DeliveryStatus.PENDING,
            DeliveryStatus.FINDING_SHIPPER, DeliveryStatus.WAIT_SHIPPER_CONFIRM, DeliveryStatus.SHIPPER_NOT_FOUND,
            DeliveryStatus.ASSIGNED);

    private static final Set<DeliveryStatus> STRONGER_THAN_NOT_FOUND = EnumSet.of(DeliveryStatus.ASSIGNED,
            DeliveryStatus.PICKED_UP, DeliveryStatus.DELIVERING, DeliveryStatus.DELIVERED, DeliveryStatus.CANCELLED);

    private CancellationPolicy() {
    }

    public static void requireShipperCancelRequest(boolean shipperRole, Long orderId) {
        if (!shipperRole) {
            throw new OfferDecisionRejected(ACCESS_DENIED, "Chỉ shipper mới có thể huỷ đơn đã nhận");
        }
        if (orderId == null) {
            throw new OfferDecisionRejected(INVALID_STATUS, "Order ID is required");
        }
    }

    public static String shipperCancelReason(String reason) {
        return reason != null && !reason.trim().isEmpty() ? reason : DEFAULT_SHIPPER_CANCEL_REASON;
    }

    public enum ShipperCancel { REPLAY, CANCEL_BATCH, RESET_TO_FINDING }

    public static ShipperCancel onShipperCancel(DeliveryStatus status, boolean inBatch, Long assignedShipperId,
                                                Long shipperId, boolean rejectReplay) {
        if (rejectReplay) return ShipperCancel.REPLAY;
        if (inBatch && status == DeliveryStatus.ASSIGNED) return ShipperCancel.CANCEL_BATCH;
        if (assignedShipperId == null || !assignedShipperId.equals(shipperId)) {
            throw new OfferDecisionRejected(ACCESS_DENIED, "Bạn không phải shipper được gán cho đơn này");
        }
        if (status != DeliveryStatus.ASSIGNED) {
            throw new OfferDecisionRejected(INVALID_STATUS,
                    "Chỉ có thể huỷ đơn khi chưa lấy hàng (trạng thái ASSIGNED). Hiện tại: " + status);
        }
        return ShipperCancel.RESET_TO_FINDING;
    }

    public enum OrderCancel { ALREADY_CANCELLED, CANCEL }

    /** Refusal after pickup must surface so Order/Saga cannot converge to CANCELLED alone. */
    public static OrderCancel onOrderCancelled(long deliveryId, DeliveryStatus status) {
        if (status == DeliveryStatus.CANCELLED) return OrderCancel.ALREADY_CANCELLED;
        if (ORDER_CANCELLABLE.contains(status)) return OrderCancel.CANCEL;
        throw new OfferDecisionRejected(INVALID_STATUS, "Cannot cancel delivery " + deliveryId + " in status " + status);
    }

    /**
     * Order asks before it cancels, because its copy of the delivery status can lag
     * behind a pickup. HOLD keeps the delivery cancellable (pickup refused) until
     * order.cancelled arrives through the usual event path or the hold expires.
     */
    public enum CancelRequest { NO_DELIVERY, ALREADY_CANCELLED, HOLD }

    public static CancelRequest onOrderCancelRequest(boolean deliveryExists, DeliveryStatus status) {
        if (!deliveryExists) return CancelRequest.NO_DELIVERY;
        if (status == DeliveryStatus.CANCELLED) return CancelRequest.ALREADY_CANCELLED;
        if (ORDER_CANCELLABLE.contains(status)) return CancelRequest.HOLD;
        throw new OfferDecisionRejected(INVALID_STATUS,
                "Shipper đã lấy hàng, không thể huỷ đơn. Trạng thái giao hàng: " + status);
    }

    public static void requirePickupNotHeldForCancel(LocalDateTime cancelHoldUntil, LocalDateTime now) {
        if (cancelHoldUntil != null && now.isBefore(cancelHoldUntil)) {
            throw new OfferDecisionRejected(INVALID_STATUS, "Khách đang huỷ đơn này, chưa thể lấy hàng");
        }
    }

    public enum NotFound { ALREADY_APPLIED, APPLY, IGNORE_STALE }

    public static NotFound onShipperNotFound(DeliveryStatus status) {
        if (status == DeliveryStatus.SHIPPER_NOT_FOUND) return NotFound.ALREADY_APPLIED;
        if (status == DeliveryStatus.FINDING_SHIPPER) return NotFound.APPLY;
        if (STRONGER_THAN_NOT_FOUND.contains(status)) return NotFound.IGNORE_STALE;
        throw new OfferDecisionRejected(INVALID_STATUS, "Contradictory shipper-not-found event in status " + status);
    }
}
