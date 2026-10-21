package com.delivery.delivery.domain;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryDomainCoverageTest {
    @Test
    void coversBatchIdentityAndAllStatusEnums() {
        UUID batch = UUID.randomUUID();
        assertEquals(batch, new BatchItemId(batch, 7L).batchId());
        assertEquals(7L, new BatchItemId(batch, 7L).deliveryId());
        assertThrows(IllegalArgumentException.class, () -> new BatchItemId(null, 7L));
        assertThrows(IllegalArgumentException.class, () -> new BatchItemId(batch, null));
        assertEquals(DeliveryBatchItemStatus.values().length, 9);
        assertEquals(DeliveryBatchStatus.values().length, 7);
        assertEquals(DeliveryExceptionStatus.values().length, 5);
        assertEquals(DeliveryProofStatus.values().length, 4);
        assertEquals(DeliveryStatus.values().length, 11);
    }
}
