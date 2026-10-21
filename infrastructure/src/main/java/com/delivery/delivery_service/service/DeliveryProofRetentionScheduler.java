package com.delivery.delivery_service.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes private proof objects only after the fixed 90-day retention period. */
@Slf4j
@Component
@ConditionalOnProperty(name = "delivery.pod.enabled", havingValue = "true")
public class DeliveryProofRetentionScheduler {

    private final DeliveryProofOfDeliveryService proofService;

    public DeliveryProofRetentionScheduler(DeliveryProofOfDeliveryService proofService) {
        this.proofService = proofService;
    }

    @Scheduled(fixedDelayString = "${delivery.pod.retention-sweep-ms:3600000}")
    public void purgeExpiredProofs() {
        int purged = proofService.purgeRetentionExpiredProofs();
        if (purged > 0) log.info("Purged {} expired proof-of-delivery object(s)", purged);
    }
}
