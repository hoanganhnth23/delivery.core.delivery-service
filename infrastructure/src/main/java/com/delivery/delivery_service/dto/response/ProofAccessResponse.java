package com.delivery.delivery_service.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/** Short-lived private read capability; no permanent object URL is returned. */
@Getter
@Setter
public class ProofAccessResponse {
    private UUID proofId;
    private String signedReadUrl;
    private LocalDateTime expiresAt;
}
