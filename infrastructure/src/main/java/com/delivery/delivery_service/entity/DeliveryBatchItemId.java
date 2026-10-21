package com.delivery.delivery_service.entity;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class DeliveryBatchItemId implements Serializable {
    private UUID batchId;
    private Long deliveryId;
}
