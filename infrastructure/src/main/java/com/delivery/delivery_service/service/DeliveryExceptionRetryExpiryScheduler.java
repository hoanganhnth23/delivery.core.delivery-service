package com.delivery.delivery_service.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded expiry worker; disabled with the exception capability by default. */
@Slf4j
@Component
@ConditionalOnProperty(name = "delivery.exception.enabled", havingValue = "true")
public class DeliveryExceptionRetryExpiryScheduler {

    private final DeliveryExceptionService exceptionService;

    public DeliveryExceptionRetryExpiryScheduler(DeliveryExceptionService exceptionService) {
        this.exceptionService = exceptionService;
    }

    @Scheduled(fixedDelayString = "${delivery.exception.retry-sweep-ms:60000}")
    public void expireRetryWindows() {
        int transitioned = exceptionService.expireRetryWindows();
        if (transitioned > 0) log.info("Moved {} expired delivery retry window(s) to RETURNING", transitioned);
    }
}
