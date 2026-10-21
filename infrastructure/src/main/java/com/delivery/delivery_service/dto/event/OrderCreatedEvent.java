package com.delivery.delivery_service.dto.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import com.delivery.identity.contracts.SimulationContext;

/**
 * ✅ Event DTO nhận từ Order Service qua Kafka theo Backend Instructions
 * Validation được thực hiện qua EventValidationService thay vì annotations
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreatedEvent {

    private Integer schemaVersion;

    private UUID eventId;

    // Order basic info
    private Long orderId;
    private Long userId;
    private Long userPrincipalId;
    private Long restaurantId;
    private String status;

    // Financial info
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
    private String paymentMethod;

    // Delivery location info
    private String deliveryAddress;
    private Double deliveryLat;
    private Double deliveryLng;

    // Restaurant info
    private String restaurantName;
    private String restaurantAddress;
    private String restaurantPhone;

    // Customer info
    private String customerName;
    private String customerPhone;
    private String notes;

    // Timestamps
    private LocalDateTime createdAt;
    private Long creatorId;
    private Long creatorPrincipalId;

    // location pickup
    private Double pickupLat;
    private Double pickupLng;
    
    // Event metadata
    private String eventType;
    private LocalDateTime eventTimestamp;
    private UUID promotionReservationId;
    private java.util.List<java.util.Map<String, Object>> appliedVouchers;
    private SimulationContext simulationContext;

}
