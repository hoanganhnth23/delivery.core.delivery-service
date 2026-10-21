package com.delivery.delivery.domain;

import java.util.function.Supplier;

import static com.delivery.delivery.domain.OfferDecisionRejected.Kind.ACCESS_DENIED;
import static com.delivery.delivery.domain.OfferDecisionRejected.Kind.INVALID_STATUS;

/** Read authorization with lazy canonical shipper resolution and explicit legacy telemetry. */
public final class DeliveryReadPolicy {
    private DeliveryReadPolicy() {}

    public static Long listActor(String role, Long legacyUserId, Supplier<Long> shipper) {
        return "SHIPPER".equals(role) ? shipper.get() : legacyUserId;
    }

    /** @return the legacy fallback metric scope, or null when no fallback was used */
    public static String requireView(String role, Long principalId, Long legacyUserId,
                                     Long customerPrincipalId, Long customerId,
                                     Long ownerPrincipalId, Long ownerId, Long assignedShipperId,
                                     Supplier<Long> shipper) {
        if ("ADMIN".equals(role)) return null;
        if ("SHIPPER".equals(role) && shipper.get().equals(assignedShipperId)) return null;
        // Guard nullable actor identities before using the shared evidence ownership policy.
        if ("USER".equals(role) && hasActor(principalId, legacyUserId, customerPrincipalId)
                && (customerPrincipalId != null || customerId != null)
                && DeliveryAccessPolicy.isCustomer(principalId, legacyUserId, customerPrincipalId, customerId)) {
            return customerPrincipalId == null ? "customer_read" : null;
        }
        if ("SHOP_OWNER".equals(role) && hasActor(principalId, legacyUserId, ownerPrincipalId)
                && DeliveryAccessPolicy.isRestaurantOwner(principalId, legacyUserId, ownerPrincipalId, ownerId)) {
            return ownerPrincipalId == null ? "restaurant_owner_read" : null;
        }
        throw new OfferDecisionRejected(ACCESS_DENIED, "Bạn không có quyền xem thông tin giao hàng này");
    }

    private static boolean hasActor(Long principalId, Long legacyUserId, Long storedPrincipalId) {
        return storedPrincipalId != null ? principalId != null : legacyUserId != null;
    }

    public static void requireShipperList(Long shipperId, Long actorId, String role) {
        if ("ADMIN".equals(role)) return;
        if ("SHIPPER".equals(role) && shipperId != null && shipperId.equals(actorId)) return;
        throw new OfferDecisionRejected(ACCESS_DENIED, "Bạn không có quyền xem danh sách delivery này");
    }

    public static void requireCurrentOffer(Long shipperId, String role) {
        if (!"SHIPPER".equals(role)) {
            throw new OfferDecisionRejected(ACCESS_DENIED, "Chỉ shipper mới có thể xem offer hiện tại");
        }
        if (shipperId == null || shipperId <= 0) {
            throw new OfferDecisionRejected(INVALID_STATUS, "Shipper ID is required");
        }
    }

    public static void requireSingleOffer(int count) {
        if (count > 1) throw new OfferDecisionRejected(INVALID_STATUS, "Shipper has multiple active offers");
    }
}
