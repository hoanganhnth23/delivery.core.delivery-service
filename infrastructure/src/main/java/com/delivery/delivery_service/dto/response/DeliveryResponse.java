package com.delivery.delivery_service.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
public class DeliveryResponse {

    private Long id;
    private Long orderId;
    private Long shipperId;
    private UUID batchId;
    private Integer batchSequence;
    private String status;
    private String pickupAddress;
    private Double pickupLat;
    private Double pickupLng;
    private String deliveryAddress;
    private Double deliveryLat;
    private Double deliveryLng;
    private Double shipperCurrentLat;
    private Double shipperCurrentLng;
    private LocalDateTime assignedAt;
    private LocalDateTime pickedUpAt;
    private LocalDateTime deliveredAt;
    private LocalDateTime estimatedDeliveryTime;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    // ✅ Pricing Information
    private BigDecimal shippingFee;           // Tổng phí vận chuyển (customer trả)
    private BigDecimal grossShippingFee;
    private BigDecimal customerShippingFee;
    private BigDecimal platformSubsidy;
    private BigDecimal shopDiscount;
    private UUID promotionReservationId;
    private BigDecimal estimatedEarnings;     // Thu nhập shipper (85% của shippingFee)
    private BigDecimal platformCommission;    // Hoa hồng platform (15% của shippingFee)

    // ✅ COD Information (tiền thu hộ khách)
    private BigDecimal totalPrice;            // Tổng tiền khách phải trả (tiền hàng + ship)
    private String paymentMethod;             // Phương thức thanh toán (COD, MOMO, etc.)
    private Long restaurantId;                // ID nhà hàng

}
