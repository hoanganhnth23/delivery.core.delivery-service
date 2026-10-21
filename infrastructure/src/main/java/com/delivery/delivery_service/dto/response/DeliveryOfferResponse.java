package com.delivery.delivery_service.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Minimal self-scoped view used by a shipper to recover an unexpired offer.
 * The selected shipper identity is derived from the JWKS-authenticated actor and
 * is intentionally not echoed in this response.
 */
@Getter
@Setter
public class DeliveryOfferResponse {

    private Long deliveryId;
    private Long orderId;
    private String status;
    private LocalDateTime expiresAt;
    private String pickupAddress;
    private Double pickupLat;
    private Double pickupLng;
    private String deliveryAddress;
    private Double deliveryLat;
    private Double deliveryLng;
    private BigDecimal shippingFee;
    private BigDecimal grossShippingFee;
    private BigDecimal customerShippingFee;
    private BigDecimal platformSubsidy;
    private BigDecimal shopDiscount;
    private UUID promotionReservationId;
    private BigDecimal estimatedEarnings;
    private BigDecimal totalPrice;
    private String paymentMethod;
    private Long restaurantId;

    /** Optional additive route metadata; absent for a single-order offer. */
    private UUID batchId;
    private Integer pickupSequence;
    private Integer dropoffSequence;
}
