package com.delivery.delivery_service.dto.response;

import com.delivery.delivery_service.entity.DeliveryProofStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
public class ProofUploadIntentResponse {
    private UUID proofId;
    private DeliveryProofStatus status;
    private String signedUploadUrl;
    private Map<String, String> requiredHeaders;
    private LocalDateTime uploadExpiresAt;
    private long maxContentLengthBytes;
}
