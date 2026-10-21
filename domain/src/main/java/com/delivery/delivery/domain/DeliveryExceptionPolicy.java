package com.delivery.delivery.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

import static com.delivery.delivery.domain.OfferDecisionRejected.Kind.ACCESS_DENIED;
import static com.delivery.delivery.domain.OfferDecisionRejected.Kind.INVALID_STATUS;

/**
 * Failed-delivery incidents after pickup: one retry within a fixed window,
 * otherwise the order returns to the restaurant, which confirms the return.
 * A successful delivery during the window resolves the incident.
 */
public final class DeliveryExceptionPolicy {

    public static final int RETRY_WINDOW_MINUTES = 15;
    public static final int MAX_REASON_LENGTH = 500;

    static final String RESOLVED_MESSAGE = "Đơn đã được giao thành công sau lần retry";

    private DeliveryExceptionPolicy() {
    }

    public static void requireEnabled(boolean enabled) {
        if (!enabled) throw new OfferDecisionRejected(INVALID_STATUS, "Luồng sự cố giao hàng chưa được bật");
    }

    public static void requireDeliveryId(Long id) {
        if (id == null || id <= 0) throw new OfferDecisionRejected(INVALID_STATUS, "Delivery ID is required");
    }

    public static boolean releaseShipper(boolean routeTerminal, Long shipperId) {
        return routeTerminal && shipperId != null;
    }

    public static BigDecimal shippingSnapshot(BigDecimal gross, BigDecimal net) {
        return gross == null ? net : gross;
    }

    public static String requireReason(String reason) {
        if (reason == null || reason.trim().isEmpty() || reason.trim().length() > MAX_REASON_LENGTH) {
            throw new OfferDecisionRejected(INVALID_STATUS, "Lý do sự cố là bắt buộc và không quá 500 ký tự");
        }
        return reason.trim();
    }

    public static void requirePostPickup(DeliveryStatus status) {
        if (status != DeliveryStatus.PICKED_UP && status != DeliveryStatus.DELIVERING) {
            throw new OfferDecisionRejected(INVALID_STATUS, "Chỉ có thể báo sự cố sau khi đã lấy hàng");
        }
    }

    public static void requireAssignedShipper(Long actorShipperId, Long assignedShipperId) {
        if (!Objects.equals(actorShipperId, assignedShipperId)) {
            throw new OfferDecisionRejected(ACCESS_DENIED, "Chỉ shipper được phân công mới có thể báo sự cố giao hàng");
        }
    }

    public static void requireRestaurantOwner(boolean restaurantOwnerRole, Long principalId, Long legacyUserId,
                                              Long ownerPrincipalId, Long ownerLegacyId) {
        if (!restaurantOwnerRole
                || !DeliveryAccessPolicy.isRestaurantOwner(principalId, legacyUserId, ownerPrincipalId, ownerLegacyId)) {
            throw new OfferDecisionRejected(ACCESS_DENIED, "Chỉ chủ nhà hàng của đơn mới có thể xác nhận hoàn trả");
        }
    }

    public static LocalDateTime retryDeadline(LocalDateTime reportedAt) {
        return reportedAt.plusMinutes(RETRY_WINDOW_MINUTES);
    }

    public enum Report { CREATE, RETURN_EXISTING, BEGIN_RETURN }

    /** The existence flag distinguishes a missing incident from a malformed historic status. */
    public static Report onReport(boolean exists, DeliveryExceptionStatus existingStatus, String existingReason,
                                  LocalDateTime retryDeadline, String reason, LocalDateTime now) {
        if (!exists) return Report.CREATE;
        if (!Objects.equals(existingReason, reason)) {
            throw new OfferDecisionRejected(INVALID_STATUS, "Sự cố giao hàng đã tồn tại với lý do khác");
        }
        return switch (existingStatus) {
            case RETRY_AVAILABLE -> retryDeadline != null && !retryDeadline.isAfter(now)
                    ? Report.BEGIN_RETURN : Report.RETURN_EXISTING;
            case RETRY_USED -> Report.BEGIN_RETURN;
            case RETURNING, RETURNED -> Report.RETURN_EXISTING;
            case RESOLVED -> throw new OfferDecisionRejected(INVALID_STATUS, RESOLVED_MESSAGE);
        };
    }

    public enum Retry { REPLAY, BEGIN_RETURN, USE_RETRY }

    public static Retry onUseRetry(DeliveryExceptionStatus status, LocalDateTime retryDeadline, LocalDateTime now) {
        if (status == DeliveryExceptionStatus.RETRY_USED) return Retry.REPLAY;
        if (status == DeliveryExceptionStatus.RETURNING || status == DeliveryExceptionStatus.RETURNED) {
            throw new OfferDecisionRejected(INVALID_STATUS, "Đơn hàng đang hoặc đã được hoàn về nhà hàng");
        }
        if (status == DeliveryExceptionStatus.RESOLVED) {
            throw new OfferDecisionRejected(INVALID_STATUS, RESOLVED_MESSAGE);
        }
        return retryDeadline.isAfter(now) ? Retry.USE_RETRY : Retry.BEGIN_RETURN;
    }

    public enum BeginReturn { ALREADY_RETURNING, START }

    public static BeginReturn onBeginReturn(DeliveryExceptionStatus status) {
        if (status == DeliveryExceptionStatus.RETURNING || status == DeliveryExceptionStatus.RETURNED) {
            return BeginReturn.ALREADY_RETURNING;
        }
        if (status == DeliveryExceptionStatus.RESOLVED) {
            throw new OfferDecisionRejected(INVALID_STATUS, RESOLVED_MESSAGE);
        }
        return BeginReturn.START;
    }

    public enum ConfirmReturn { REPLAY, RETURN }

    public static ConfirmReturn onConfirmReturn(DeliveryExceptionStatus status, DeliveryStatus deliveryStatus) {
        if (status == DeliveryExceptionStatus.RETURNED) return ConfirmReturn.REPLAY;
        if (status != DeliveryExceptionStatus.RETURNING || deliveryStatus != DeliveryStatus.RETURNING) {
            throw new OfferDecisionRejected(INVALID_STATUS, "Đơn hàng chưa ở trạng thái chờ xác nhận hoàn trả");
        }
        return ConfirmReturn.RETURN;
    }

    public enum Sweep { SKIP, RESOLVE, BEGIN_RETURN }

    /** An unused retry whose window elapsed returns, unless the delivery already succeeded. */
    public static Sweep onRetryWindowSweep(DeliveryExceptionStatus status, LocalDateTime retryDeadline,
                                           DeliveryStatus deliveryStatus, LocalDateTime now) {
        if (status != DeliveryExceptionStatus.RETRY_AVAILABLE || retryDeadline == null || retryDeadline.isAfter(now)) {
            return Sweep.SKIP;
        }
        return deliveryStatus == DeliveryStatus.DELIVERED ? Sweep.RESOLVE : Sweep.BEGIN_RETURN;
    }

    public enum Delivered { NONE, RESOLVE }

    /** A successful delivery resolves an open incident; it cannot complete a returning order. */
    public static Delivered onSuccessfulDelivery(DeliveryExceptionStatus status) {
        if (status == null) return Delivered.NONE;
        if (status == DeliveryExceptionStatus.RETRY_AVAILABLE || status == DeliveryExceptionStatus.RETRY_USED) {
            return Delivered.RESOLVE;
        }
        if (status == DeliveryExceptionStatus.RETURNING || status == DeliveryExceptionStatus.RETURNED) {
            throw new OfferDecisionRejected(INVALID_STATUS, "Không thể hoàn tất đơn đang trong luồng hoàn trả");
        }
        return Delivered.NONE;
    }

    /**
     * Immutable money snapshot carried by the incident: gross shipping (falling
     * back to the net fee) and the full discount delta subtotal + shipping - total.
     *
     * @return the discount amount
     */
    public static BigDecimal requireMoneySnapshot(BigDecimal subtotal, BigDecimal shipping, BigDecimal total) {
        BigDecimal discount = subtotal == null || shipping == null || total == null
                ? null : subtotal.add(shipping).subtract(total);
        if (subtotal == null || shipping == null || total == null
                || subtotal.signum() < 0 || shipping.signum() < 0 || total.signum() <= 0
                || discount.signum() < 0) {
            throw new OfferDecisionRejected(INVALID_STATUS, "Sự cố giao hàng cần snapshot tiền tệ bất biến hợp lệ");
        }
        return discount;
    }
}
