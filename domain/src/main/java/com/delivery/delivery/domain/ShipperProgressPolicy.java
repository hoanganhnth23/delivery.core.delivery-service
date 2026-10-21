package com.delivery.delivery.domain;

import java.util.function.Function;

import static com.delivery.delivery.domain.OfferDecisionRejected.Kind.ACCESS_DENIED;
import static com.delivery.delivery.domain.OfferDecisionRejected.Kind.INVALID_STATUS;

/**
 * Shipper-driven progress: only the assigned shipper advances a delivery,
 * strictly ASSIGNED → PICKED_UP → DELIVERING → DELIVERED; a retried request
 * for the current status is a replay.
 */
public final class ShipperProgressPolicy {

    public enum Progress { REPLAY, ADVANCE }

    private ShipperProgressPolicy() {
    }

    public static void requireAssignedShipper(boolean shipperRole, Long actorId, Long assignedShipperId) {
        if (shipperRole && actorId != null && actorId.equals(assignedShipperId)) {
            return;
        }
        throw new OfferDecisionRejected(ACCESS_DENIED,
                "Chỉ shipper được phân công mới có thể cập nhật trạng thái giao hàng");
    }

    public static void requireShipperTarget(DeliveryStatus requested) {
        if (requested != DeliveryStatus.PICKED_UP && requested != DeliveryStatus.DELIVERING
                && requested != DeliveryStatus.DELIVERED) {
            throw new OfferDecisionRejected(INVALID_STATUS,
                    "Shipper chỉ có thể cập nhật PICKED_UP, DELIVERING hoặc DELIVERED");
        }
    }

    /** @param describe human-readable status names used in the refusal message */
    public static Progress decide(DeliveryStatus current, DeliveryStatus requested,
                                  Function<DeliveryStatus, String> describe) {
        if (requested == current) {
            return Progress.REPLAY;
        }
        DeliveryStatus expected = switch (current) {
            case ASSIGNED -> DeliveryStatus.PICKED_UP;
            case PICKED_UP -> DeliveryStatus.DELIVERING;
            case DELIVERING -> DeliveryStatus.DELIVERED;
            default -> null;
        };
        if (expected != requested) {
            throw new OfferDecisionRejected(INVALID_STATUS, "Không thể chuyển từ trạng thái "
                    + describe.apply(current) + " sang " + describe.apply(requested));
        }
        return Progress.ADVANCE;
    }

    /** The terminal handoff is gated by proof-of-delivery rules. */
    public static boolean requiresProofGate(DeliveryStatus requested) {
        return requested == DeliveryStatus.DELIVERED;
    }

    /** The shipper becomes available once a delivery (or its whole batch) is delivered. */
    public static boolean releasesShipper(DeliveryStatus requested, boolean hasShipper, boolean batchCompleted) {
        return requested == DeliveryStatus.DELIVERED && hasShipper && batchCompleted;
    }
}
