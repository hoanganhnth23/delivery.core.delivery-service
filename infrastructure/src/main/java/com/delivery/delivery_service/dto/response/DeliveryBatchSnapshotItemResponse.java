package com.delivery.delivery_service.dto.response;

import com.delivery.delivery_service.entity.DeliveryBatchItemStatus;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DeliveryBatchSnapshotItemResponse {
    private Long deliveryId;
    private Long orderId;
    private int pickupSequence;
    private int dropoffSequence;
    private DeliveryBatchItemStatus itemStatus;
    private DeliveryResponse delivery;
}
