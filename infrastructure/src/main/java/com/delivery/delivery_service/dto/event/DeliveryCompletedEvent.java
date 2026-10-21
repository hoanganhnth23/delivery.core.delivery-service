package com.delivery.delivery_service.dto.event;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.delivery.identity.contracts.SimulationContext;

/**
 * ✅ Event được publish khi delivery hoàn thành
 * Shipper Service sẽ listen để tự động cộng tiền vào balance
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryCompletedEvent {
    private java.util.UUID eventId;
    private String eventType;
    private SimulationContext simulationContext;
    
    private Long deliveryId;
    private Long orderId;
    private Long shipperId;
    private Long restaurantId;
    
    private BigDecimal shippingFee; // Tổng phí ship khách hàng trả
    private BigDecimal grossShippingFee;
    private BigDecimal customerShippingFee;
    private BigDecimal subtotalPrice;
    private BigDecimal shopDiscount;
    private BigDecimal platformSubsidy;
    private BigDecimal shippingDiscount;
    private BigDecimal totalPrice; // Canonical customer total snapshotted from Order
    private BigDecimal shipperEarnings; // Số tiền shipper thực nhận (85% của shippingFee)
    private BigDecimal restaurantEarnings; // Số tiền nhà hàng thực nhận (Food Price - Commission)
    private BigDecimal restaurantCommission; // Hoa hồng từ nhà hàng (ví dụ 20% giá món)
    private BigDecimal shippingCommission; // Hoa hồng từ phí ship (ví dụ 15% phí ship)
    private BigDecimal totalPlatformEarnings; // Tổng cộng hoa hồng app nhận được
    
    private LocalDateTime deliveredAt;
    private String deliveryAddress;
    private String paymentMethod;     // "COD" or "ONLINE"
    
    // Additional info for transaction description
    private String restaurantName;
    private String customerName;
}
