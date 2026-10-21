package com.delivery.delivery_service.dto.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Dedicated post-pickup exception contract. It intentionally does not reuse
 * delivery.status-updated because older Saga consumers reject unknown status
 * vocabulary.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryExceptionReportedEvent {
    private UUID eventId;
    private String eventType;
    private LocalDateTime occurredAt;
    private UUID exceptionId;
    private Long deliveryId;
    private Long orderId;
    private Long userId;
    private Long userPrincipalId;
    private Long restaurantId;
    private Long shipperId;
    private String previousDeliveryStatus;
    private String currentDeliveryStatus;
    private String exceptionStatus;
    private String reason;
    private String paymentMethod;
    private BigDecimal subtotalPrice;
    private BigDecimal discountAmount;
    private BigDecimal shippingFee;
    private BigDecimal totalPrice;
}
