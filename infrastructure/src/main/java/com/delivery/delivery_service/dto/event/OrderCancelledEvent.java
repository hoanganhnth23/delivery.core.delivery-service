package com.delivery.delivery_service.dto.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ✅ Event DTO nhận từ Kafka khi order bị hủy từ Order Service
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderCancelledEvent {

    private UUID eventId;
    
    // Order basic info
    private Long orderId;
    private Long userId;
    private Long userPrincipalId;
    private Long restaurantId;
    private String previousStatus;
    private String currentStatus; // CANCELLED
    
    // Cancellation info
    private String cancelReason;
    private Long cancelledBy; // userId who cancelled
    private String cancelledBySource;
    private String cancelReasonCode;
    private LocalDateTime cancelledAt;
    
    // Delivery related
    private Long shipperId; // null if no shipper assigned
    private Boolean hasActiveDelivery;
    private UUID voucherReservationId;
    private UUID promotionReservationId;
    private UUID flashSaleReservationId;
    private List<Map<String, Object>> items;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Carried through for downstream compensation consumers; Delivery does not mutate it.
    private BigDecimal subtotalPrice;
    private BigDecimal discountAmount;
    private BigDecimal shippingFee;
    private BigDecimal totalPrice;
    private BigDecimal itemDiscount;
    private BigDecimal shippingDiscount;
    private BigDecimal customerShippingFee;
    private BigDecimal grossShippingFee;
    private BigDecimal platformSubsidy;
    private BigDecimal shopDiscount;
    private java.util.List<java.util.Map<String, Object>> appliedVouchers;
    private String paymentMethod;
}
