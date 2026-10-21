package com.delivery.delivery_service.dto.request;

import lombok.Data;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * ✅ Request DTO cho shipper accept/reject delivery theo Backend Instructions
 */
@Data
public class AcceptDeliveryRequest {
    
    @NotNull(message = "orderId is required")
    @Positive(message = "orderId must be positive")
    private Long orderId; // Order ID to accept/reject

    @NotNull(message = "action is required")
    @Pattern(regexp = "ACCEPT|REJECT", message = "action must be ACCEPT or REJECT")
    private String action; // "ACCEPT" hoặc "REJECT"

    @Size(max = 500)
    private String notes; // Optional notes from shipper (required for reject)

    @Size(max = 500)
    private String rejectReason; // Reason for rejection (if action = REJECT)

    @DecimalMin(value = "0.0", inclusive = false)
    @DecimalMax("240.0")
    private Double estimatedPickupTime; // Shipper's estimated pickup time in minutes (for ACCEPT)

    @DecimalMin("8.0")
    @DecimalMax("24.0")
    private Double currentLat; // Shipper's current latitude

    @DecimalMin("102.0")
    @DecimalMax("110.0")
    private Double currentLng; // Shipper's current longitude
}
