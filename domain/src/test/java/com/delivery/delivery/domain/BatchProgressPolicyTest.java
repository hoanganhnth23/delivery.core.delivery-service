package com.delivery.delivery.domain;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static com.delivery.delivery.domain.BatchProgressPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class BatchProgressPolicyTest {
    @Test void scopeAndInactiveGuards() {
        UUID id=UUID.randomUUID();
        assertTrue(unscoped(false,id,1L,true)); assertTrue(unscoped(true,null,1L,true));
        assertTrue(unscoped(true,id,null,true)); assertTrue(unscoped(true,id,1L,false)); assertFalse(unscoped(true,id,1L,true));
        assertTrue(inactive(false,null));
        for(DeliveryBatchStatus status:DeliveryBatchStatus.values()) assertEquals(status==DeliveryBatchStatus.RETIRED || status==DeliveryBatchStatus.CANCELLED,inactive(true,status));
    }
    @Test void everyProjectionAndAggregateTransition() {
        for(DeliveryStatus status:DeliveryStatus.values()) {
            DeliveryBatchItemStatus expected=switch(status) {
                case PICKED_UP -> DeliveryBatchItemStatus.PICKED_UP;
                case DELIVERING -> DeliveryBatchItemStatus.DELIVERING;
                case DELIVERED -> DeliveryBatchItemStatus.DELIVERED;
                case RETURNING -> DeliveryBatchItemStatus.RETURNING;
                case RETURNED -> DeliveryBatchItemStatus.RETURNED;
                case CANCELLED -> DeliveryBatchItemStatus.CANCELLED;
                default -> null;
            };
            assertEquals(expected,itemStatusFor(status));
            DeliveryBatchStatus next=status==DeliveryStatus.PICKED_UP ? DeliveryBatchStatus.PICKED_UP
                    : status==DeliveryStatus.DELIVERING || status==DeliveryStatus.DELIVERED ? DeliveryBatchStatus.DELIVERING : DeliveryBatchStatus.ACCEPTED;
            assertEquals(new Progress(false,false,next),onProgress(List.of(DeliveryBatchItemStatus.ACCEPTED),DeliveryBatchStatus.ACCEPTED,status));
        }
        assertEquals(DeliveryBatchItemStatus.RETURNING,returnStatus(false)); assertEquals(DeliveryBatchItemStatus.RETURNED,returnStatus(true));
        for(DeliveryBatchItemStatus item:DeliveryBatchItemStatus.values()) {
            boolean terminal=item==DeliveryBatchItemStatus.DELIVERED || item==DeliveryBatchItemStatus.RETURNED;
            assertEquals(terminal,onProgress(List.of(item),DeliveryBatchStatus.DELIVERING,DeliveryStatus.DELIVERED).allTerminal());
        }
        assertFalse(onProgress(List.of(),DeliveryBatchStatus.ACCEPTED,DeliveryStatus.DELIVERED).allTerminal());
        var terminal=List.of(DeliveryBatchItemStatus.DELIVERED,DeliveryBatchItemStatus.RETURNED);
        assertEquals(new Progress(true,true,DeliveryBatchStatus.COMPLETED),onProgress(terminal,DeliveryBatchStatus.DELIVERING,DeliveryStatus.DELIVERED));
        assertEquals(new Progress(true,false,DeliveryBatchStatus.COMPLETED),onProgress(terminal,DeliveryBatchStatus.COMPLETED,DeliveryStatus.DELIVERED));
        assertEquals(new Progress(true,true,DeliveryBatchStatus.COMPLETED),onReturn(terminal,DeliveryBatchStatus.DELIVERING,true));
        assertEquals(new Progress(true,false,DeliveryBatchStatus.COMPLETED),onReturn(terminal,DeliveryBatchStatus.COMPLETED,false));
        assertEquals(new Progress(false,false,DeliveryBatchStatus.DELIVERING),onReturn(List.of(),DeliveryBatchStatus.PICKED_UP,false));
        assertEquals(new Progress(false,false,DeliveryBatchStatus.PICKED_UP),onReturn(List.of(DeliveryBatchItemStatus.RETURNING),DeliveryBatchStatus.PICKED_UP,true));
    }
}
