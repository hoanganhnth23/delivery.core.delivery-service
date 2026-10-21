package com.delivery.delivery.domain;

import java.util.UUID;

public record BatchItemId(UUID batchId, Long deliveryId) {
    public BatchItemId {
        if (batchId == null || deliveryId == null) throw new IllegalArgumentException("batchId and deliveryId are required");
    }
}
