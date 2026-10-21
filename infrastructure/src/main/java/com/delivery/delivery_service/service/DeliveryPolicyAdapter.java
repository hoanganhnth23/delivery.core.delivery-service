package com.delivery.delivery_service.service;

import com.delivery.delivery.domain.OfferDecisionRejected;
import com.delivery.delivery_service.common.constants.RoleConstants;
import com.delivery.delivery.domain.DeliveryAccessPolicy;
import com.delivery.delivery_service.exception.AccessDeniedException;
import com.delivery.delivery_service.exception.InvalidStatusException;
import java.util.function.Supplier;

/** Translates framework-free refusals and transport identity/status values. */
final class DeliveryPolicyAdapter {
    private DeliveryPolicyAdapter() {}
    static void policy(Runnable rule) { decision(() -> { rule.run(); return null; }); }
    static <T> T decision(Supplier<T> rule) {
        try { return rule.get(); }
        catch (OfferDecisionRejected rejected) {
            throw rejected.kind() == OfferDecisionRejected.Kind.ACCESS_DENIED
                    ? new AccessDeniedException(rejected.getMessage())
                    : new InvalidStatusException(rejected.getMessage());
        }
    }
    static com.delivery.delivery.domain.DeliveryStatus domain(com.delivery.delivery_service.entity.DeliveryStatus status) {
        return status == null ? null : com.delivery.delivery.domain.DeliveryStatus.valueOf(status.name());
    }
    static com.delivery.delivery.domain.DeliveryExceptionStatus domain(com.delivery.delivery_service.entity.DeliveryExceptionStatus status) {
        return status == null ? null : com.delivery.delivery.domain.DeliveryExceptionStatus.valueOf(status.name());
    }
    static com.delivery.delivery.domain.DeliveryBatchStatus domain(com.delivery.delivery_service.entity.DeliveryBatchStatus status) {
        return status == null ? null : com.delivery.delivery.domain.DeliveryBatchStatus.valueOf(status.name());
    }
    static com.delivery.delivery.domain.DeliveryBatchItemStatus domain(com.delivery.delivery_service.entity.DeliveryBatchItemStatus status) {
        return status == null ? null : com.delivery.delivery.domain.DeliveryBatchItemStatus.valueOf(status.name());
    }
    static DeliveryAccessPolicy.Viewer viewer(String role) {
        return RoleConstants.ADMIN.equals(role) ? DeliveryAccessPolicy.Viewer.ADMIN
                : RoleConstants.SHIPPER.equals(role) ? DeliveryAccessPolicy.Viewer.SHIPPER
                : RoleConstants.USER.equals(role) ? DeliveryAccessPolicy.Viewer.CUSTOMER
                : RoleConstants.RESTAURANT_OWNER.equals(role) ? DeliveryAccessPolicy.Viewer.RESTAURANT_OWNER
                : DeliveryAccessPolicy.Viewer.OTHER;
    }
}
