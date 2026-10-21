package com.delivery.delivery.domain;

import java.util.List;
import java.util.UUID;

/** Item projection and batch completion decisions; the host applies writes and events. */
public final class BatchProgressPolicy {
    private BatchProgressPolicy() {}
    public static boolean unscoped(boolean deliveryPresent, UUID batch, Long delivery, boolean statusPresent) {
        return !deliveryPresent || batch == null || delivery == null || !statusPresent;
    }
    public static boolean inactive(boolean present, DeliveryBatchStatus status) {
        return !present || status == DeliveryBatchStatus.RETIRED || status == DeliveryBatchStatus.CANCELLED;
    }
    public static DeliveryBatchItemStatus itemStatusFor(DeliveryStatus status) {
        return switch (status) {
            case PICKED_UP -> DeliveryBatchItemStatus.PICKED_UP;
            case DELIVERING -> DeliveryBatchItemStatus.DELIVERING;
            case DELIVERED -> DeliveryBatchItemStatus.DELIVERED;
            case RETURNING -> DeliveryBatchItemStatus.RETURNING;
            case RETURNED -> DeliveryBatchItemStatus.RETURNED;
            case CANCELLED -> DeliveryBatchItemStatus.CANCELLED;
            default -> null;
        };
    }
    public static DeliveryBatchItemStatus returnStatus(boolean returned) {
        return returned ? DeliveryBatchItemStatus.RETURNED : DeliveryBatchItemStatus.RETURNING;
    }
    public record Progress(boolean allTerminal, boolean newlyCompleted, DeliveryBatchStatus nextStatus) {}
    private static boolean allTerminal(List<DeliveryBatchItemStatus> items) {
        return !items.isEmpty() && items.stream().allMatch(item ->
                item == DeliveryBatchItemStatus.DELIVERED || item == DeliveryBatchItemStatus.RETURNED);
    }
    public static Progress onProgress(List<DeliveryBatchItemStatus> items, DeliveryBatchStatus batch, DeliveryStatus delivery) {
        boolean terminal = allTerminal(items);
        DeliveryBatchStatus next = batch;
        if (terminal) next = DeliveryBatchStatus.COMPLETED;
        else if (delivery == DeliveryStatus.DELIVERING || delivery == DeliveryStatus.DELIVERED) next = DeliveryBatchStatus.DELIVERING;
        else if (delivery == DeliveryStatus.PICKED_UP) next = DeliveryBatchStatus.PICKED_UP;
        return new Progress(terminal, terminal && batch != DeliveryBatchStatus.COMPLETED, next);
    }
    public static Progress onReturn(List<DeliveryBatchItemStatus> items, DeliveryBatchStatus batch, boolean returned) {
        boolean terminal = allTerminal(items);
        DeliveryBatchStatus next = terminal ? DeliveryBatchStatus.COMPLETED : !returned ? DeliveryBatchStatus.DELIVERING : batch;
        return new Progress(terminal, terminal && batch != DeliveryBatchStatus.COMPLETED, next);
    }
}
