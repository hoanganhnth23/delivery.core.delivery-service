package com.delivery.delivery_service.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class AcceptBatchRequest {
    @NotNull
    private UUID batchId;
    private String notes;
    private Double currentLat;
    private Double currentLng;
}
