package com.delivery.delivery_service.service;

import com.delivery.delivery_service.entity.DeliveryProofStatus;
import com.delivery.delivery_service.exception.InvalidStatusException;
import com.delivery.delivery_service.repository.DeliveryProofOfDeliveryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Enforces the POD precondition only when the explicitly gated capability is on. */
@Service
public class DeliveryProofGate {

    private final DeliveryProofOfDeliveryRepository proofRepository;

    @Value("${delivery.pod.enabled:false}")
    private boolean podEnabled;

    public DeliveryProofGate(DeliveryProofOfDeliveryRepository proofRepository) {
        this.proofRepository = proofRepository;
    }

    public void assertTerminalHandoffAllowed(Long deliveryId) {
        if (!podEnabled) return;
        if (deliveryId == null || !proofRepository.existsByDeliveryIdAndStatus(
                deliveryId, DeliveryProofStatus.CONFIRMED)) {
            throw new InvalidStatusException("Cần xác nhận ảnh bằng chứng giao hàng trước khi hoàn tất đơn");
        }
    }
}
