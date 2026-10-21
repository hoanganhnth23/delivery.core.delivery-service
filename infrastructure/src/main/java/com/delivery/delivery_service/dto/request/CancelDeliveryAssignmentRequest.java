package com.delivery.delivery_service.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CancelDeliveryAssignmentRequest {

    @NotNull(message = "orderId is required")
    @Positive(message = "orderId must be positive")
    private Long orderId;

    @Size(max = 500)
    private String reason;
}
