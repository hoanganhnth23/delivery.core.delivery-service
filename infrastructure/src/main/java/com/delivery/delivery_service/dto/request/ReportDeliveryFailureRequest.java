package com.delivery.delivery_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ReportDeliveryFailureRequest {
    @NotBlank(message = "reason is required")
    @Size(max = 500, message = "reason must not exceed 500 characters")
    private String reason;
}
