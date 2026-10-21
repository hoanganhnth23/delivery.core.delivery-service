package com.delivery.delivery.domain;

import static com.delivery.delivery.domain.OfferDecisionRejected.Kind.ACCESS_DENIED;

/** Who may see delivery-scoped evidence and incidents. */
public final class DeliveryAccessPolicy {

    public enum Viewer { ADMIN, SHIPPER, CUSTOMER, RESTAURANT_OWNER, OTHER }

    private DeliveryAccessPolicy() {
    }

    /** Principal identity wins; the legacy owner id applies only to unmigrated rows. */
    public static boolean isRestaurantOwner(Long principalId, Long legacyUserId, Long ownerPrincipalId, Long ownerLegacyId) {
        return (ownerPrincipalId != null && ownerPrincipalId.equals(principalId))
                || (ownerPrincipalId == null && ownerLegacyId != null && ownerLegacyId.equals(legacyUserId));
    }

    public static boolean isCustomer(Long principalId, Long legacyUserId, Long customerPrincipalId, Long creatorId) {
        return (customerPrincipalId != null && customerPrincipalId.equals(principalId))
                || (customerPrincipalId == null && creatorId.equals(legacyUserId));
    }

    /**
     * @param assignedShipperCheck evaluated only for a SHIPPER viewer; must throw when not assigned
     */
    public static void requireViewer(Viewer viewer, Long principalId, Long legacyUserId,
                                     Long customerPrincipalId, Long creatorId,
                                     Long ownerPrincipalId, Long ownerLegacyId,
                                     Runnable assignedShipperCheck, String deniedMessage) {
        switch (viewer) {
            case ADMIN -> {
                return;
            }
            case SHIPPER -> {
                assignedShipperCheck.run();
                return;
            }
            case CUSTOMER -> {
                if (isCustomer(principalId, legacyUserId, customerPrincipalId, creatorId)) return;
            }
            case RESTAURANT_OWNER -> {
                if (isRestaurantOwner(principalId, legacyUserId, ownerPrincipalId, ownerLegacyId)) return;
            }
            default -> {
            }
        }
        throw new OfferDecisionRejected(ACCESS_DENIED, deniedMessage);
    }
}
