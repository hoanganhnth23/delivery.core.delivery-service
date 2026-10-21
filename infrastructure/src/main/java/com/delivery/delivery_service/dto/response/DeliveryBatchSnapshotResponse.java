package com.delivery.delivery_service.dto.response;

import com.delivery.delivery_service.entity.DeliveryBatchStatus;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Durable, self-scoped representation of a batch route. */
@Getter
@Setter
public class DeliveryBatchSnapshotResponse {
    private UUID batchId;
    private DeliveryBatchStatus status;
    private int routeVersion;
    private LocalDateTime expiresAt;
    private BigDecimal totalCodAmount;
    private List<DeliveryBatchSnapshotItemResponse> items;
}
