package com.delivery.delivery_service.dto.response;

import com.delivery.delivery_service.entity.DeliveryExceptionStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
public class DeliveryExceptionResponse {
    private UUID exceptionId;
    private Long deliveryId;
    private DeliveryExceptionStatus status;
    private String reason;
    private LocalDateTime reportedAt;
    private LocalDateTime retryDeadlineAt;
    private LocalDateTime retryUsedAt;
    private LocalDateTime returningAt;
    private LocalDateTime returnedAt;
}
