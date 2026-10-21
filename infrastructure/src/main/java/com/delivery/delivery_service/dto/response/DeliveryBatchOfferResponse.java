package com.delivery.delivery_service.dto.response;

import com.delivery.delivery_service.entity.DeliveryBatchStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class DeliveryBatchOfferResponse {
    private UUID batchId;
    private DeliveryBatchStatus status;
    private int routeVersion;
    private LocalDateTime expiresAt;
    private BigDecimal totalCodAmount;
    private List<DeliveryOfferResponse> offers;
}
