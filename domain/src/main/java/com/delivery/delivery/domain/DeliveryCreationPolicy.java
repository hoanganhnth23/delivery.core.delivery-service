package com.delivery.delivery.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** Existing create-command admission, replay identity and immutable snapshot defaults. */
public final class DeliveryCreationPolicy {
    private DeliveryCreationPolicy() {}
    public record Create(Long orderId, UUID eventId, Long customerId, Long restaurantId, Long ownerId,
                         String pickupAddress, Double pickupLat, Double pickupLng,
                         String deliveryAddress, Double deliveryLat, Double deliveryLng,
                         BigDecimal shippingFee, BigDecimal totalPrice, BigDecimal grossShippingFee,
                         UUID promotionReservationId, String paymentMethod) {}
    public record Money(BigDecimal grossShippingFee, BigDecimal customerShippingFee,
                        BigDecimal itemDiscount, BigDecimal shopDiscount) {}

    public static void requireEvent(Create event) {
        if (event == null) {
            throw new OfferDecisionRejected(OfferDecisionRejected.Kind.INVALID_STATUS, "OrderCreatedEvent is required");
        }
        if (event.eventId() == null) {
            throw new OfferDecisionRejected(OfferDecisionRejected.Kind.INVALID_STATUS, "Create-delivery eventId is required");
        }
        if (!positive(event.orderId())) {
            throw new OfferDecisionRejected(OfferDecisionRejected.Kind.INVALID_STATUS, "Create-delivery orderId must be positive");
        }
        if (!positive(event.customerId())) {
            throw new OfferDecisionRejected(OfferDecisionRejected.Kind.INVALID_STATUS, "Create-delivery userId must be positive");
        }
        if (!positive(event.restaurantId())) {
            throw new OfferDecisionRejected(OfferDecisionRejected.Kind.INVALID_STATUS, "Create-delivery restaurantId must be positive");
        }
        if (!positive(event.ownerId())) {
            throw new OfferDecisionRejected(OfferDecisionRejected.Kind.INVALID_STATUS, "Create-delivery creatorId must be positive");
        }
    }


    public static DeliveryStatus initialStatus() { return DeliveryStatus.FINDING_SHIPPER; }

    public static Money money(BigDecimal shippingFee, BigDecimal grossShippingFee,
                              BigDecimal customerShippingFee, BigDecimal discountAmount,
                              BigDecimal itemDiscount, BigDecimal shopDiscount) {
        return new Money(grossShippingFee == null ? shippingFee : grossShippingFee,
                customerShippingFee == null ? shippingFee : customerShippingFee,
                itemDiscount == null ? discountAmount : itemDiscount,
                shopDiscount == null ? discountAmount : shopDiscount);
    }

    public static void requireReplay(Long deliveryId, Create existing, Create event) {
        boolean matches = Objects.equals(existing.eventId(), event.eventId())
                && Objects.equals(existing.customerId(), event.customerId())
                && Objects.equals(existing.restaurantId(), event.restaurantId())
                && Objects.equals(existing.ownerId(), event.ownerId())
                && Objects.equals(existing.pickupAddress(), event.pickupAddress())
                && Objects.equals(existing.pickupLat(), event.pickupLat())
                && Objects.equals(existing.pickupLng(), event.pickupLng())
                && Objects.equals(existing.deliveryAddress(), event.deliveryAddress())
                && Objects.equals(existing.deliveryLat(), event.deliveryLat())
                && Objects.equals(existing.deliveryLng(), event.deliveryLng())
                && sameAmount(existing.shippingFee(), event.shippingFee())
                && sameAmount(existing.totalPrice(), event.totalPrice())
                && sameAmount(existing.grossShippingFee() == null
                                ? existing.shippingFee() : existing.grossShippingFee(),
                        event.grossShippingFee() == null
                                ? event.shippingFee() : event.grossShippingFee())
                && Objects.equals(existing.promotionReservationId(), event.promotionReservationId())
                && Objects.equals(existing.paymentMethod(), event.paymentMethod());
        if (!matches) {
            throw new OfferDecisionRejected(OfferDecisionRejected.Kind.INVALID_STATUS,
                    "Create-delivery replay conflicts with existing delivery " + deliveryId + " for order " + event.orderId());
        }
    }
    private static boolean sameAmount(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }
    private static boolean positive(Long value) { return value != null && value > 0; }
}
