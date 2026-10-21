package com.delivery.delivery_service.dto.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.math.BigDecimal;

/**
 * ✅ Event nhận từ match-service khi tìm được shipper
 * Dùng cho cả delivery-service (cache waiting state) và notification-service (notify shipper)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShipperFoundEvent {

    private UUID eventId;
    
    private Long deliveryId;
    private Long orderId;
    private List<ShipperMatchResult> availableShippers;
    private LocalDateTime foundAt;
    private Integer waitingTimeoutSeconds;
    private String matchingSessionId;
    
    // ✅ Additional info for notification-service
    private String restaurantName;
    private String pickupAddress;
    private String deliveryAddress;
    private Double pickupLat;
    private Double pickupLng;
    private Double deliveryLat;
    private Double deliveryLng;

    private Boolean batchOffer;
    private UUID batchId;
    private List<BatchItem> batchItems;
    private List<UUID> codHoldIds;
    private Integer batchWave;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchItem {
        private Long deliveryId;
        private Long orderId;
        private Integer pickupSequence;
        private Integer dropoffSequence;
        private BigDecimal totalPrice;
        private UUID matchingSessionId;
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ShipperMatchResult {
        private Long shipperId;
        private String shipperName;
        private String shipperPhone;
        private Double distanceKm;
        private Double latitude;
        private Double longitude;
        private Double rating;
        private Boolean isOnline;
    }
}
