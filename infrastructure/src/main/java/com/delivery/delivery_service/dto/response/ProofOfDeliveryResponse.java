package com.delivery.delivery_service.dto.response;

import com.delivery.delivery_service.entity.DeliveryProofStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
public class ProofOfDeliveryResponse {
    private UUID proofId;
    private DeliveryProofStatus status;
    private String contentType;
    private Long sizeBytes;
    private LocalDateTime confirmedAt;
    private LocalDateTime retentionExpiresAt;
}
