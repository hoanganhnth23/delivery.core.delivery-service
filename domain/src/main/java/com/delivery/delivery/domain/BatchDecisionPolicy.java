package com.delivery.delivery.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import static com.delivery.delivery.domain.OfferDecisionRejected.Kind.*;

/** Existing batch offer, acceptance and retirement rules, independent of persistence. */
public final class BatchDecisionPolicy {
    private BatchDecisionPolicy() {}
    private static OfferDecisionRejected invalid(String message) { return new OfferDecisionRejected(INVALID_STATUS, message); }
    private static OfferDecisionRejected denied(String message) { return new OfferDecisionRejected(ACCESS_DENIED, message); }
    public static void requireEnabled(boolean enabled) {
        if (!enabled) throw invalid("Delivery batch dispatch is disabled");
    }
    /** The first shipper lookup stays lazy, preserving envelope check order. */
    public static void requireOffer(boolean present, Boolean batchOffer, UUID id, Integer items,
                                    Integer shippers, Supplier<Long> firstShipper) {
        if (!present || !Boolean.TRUE.equals(batchOffer) || id == null || items == null || items == 0
                || items > 3 || shippers == null || shippers != 1 || firstShipper.get() == null) {
            throw invalid("Invalid batch shipper offer event");
        }
    }
    public static void requireSession(boolean itemPresent, UUID session) {
        if (!itemPresent || session == null) throw invalid("Batch delivery IDs must be unique and complete");
    }
    public static LocalDateTime expiresAt(LocalDateTime foundAt, Integer timeout, LocalDateTime now) {
        LocalDateTime found = foundAt == null ? now : foundAt;
        int seconds = timeout == null ? 180 : Math.max(1, Math.min(timeout, 180));
        LocalDateTime expires = found.plusSeconds(seconds);
        if (!expires.isAfter(now)) throw invalid("Batch shipper offer already expired");
        return expires;
    }
    public static void requireReplay(Long shipper, Long existingShipper, DeliveryBatchStatus status) {
        if (!shipper.equals(existingShipper) || status != DeliveryBatchStatus.OFFERED)
            throw invalid("Batch offer replay conflicts with existing batch");
    }
    public static int wave(Integer wave) { return wave == null ? 0 : Math.max(0, wave); }
    public static int nextWave(int wave) { return Math.max(1, wave + 1); }
    public static void requireHolds(Integer holds, int items) {
        if (holds == null || holds != items) throw invalid("Batch COD holds are incomplete");
    }
    public static void requireOrder(Long order, Long deliveryOrder) {
        if (!order.equals(deliveryOrder)) throw invalid("Batch order does not match delivery");
    }
    public static void requireAvailable(UUID batch, DeliveryStatus status) {
        if (batch != null || (status != DeliveryStatus.FINDING_SHIPPER && status != DeliveryStatus.WAIT_SHIPPER_CONFIRM))
            throw invalid("Delivery is not available for batch assignment");
    }
    public static BigDecimal cod(BigDecimal price) { return price == null ? BigDecimal.ZERO : price; }
    public static void requireAcceptActor(boolean shipperRole) {
        if (!shipperRole) throw denied("Chỉ shipper mới có thể nhận batch");
    }
    public static void requireAcceptRequest(boolean requestPresent, UUID batch, Long shipper) {
        if (!requestPresent || batch == null || shipper == null || shipper <= 0)
            throw invalid("Batch ID and shipper are required");
    }
    public static void requireOwner(Long actor, Long assigned) {
        if (!actor.equals(assigned)) throw denied("Batch không thuộc shipper này");
    }
    public enum Accept { REPLAY, ACCEPT }
    public static Accept onAccept(DeliveryBatchStatus status, LocalDateTime expires, LocalDateTime now) {
        if (status == DeliveryBatchStatus.ACCEPTED) return Accept.REPLAY;
        if (status != DeliveryBatchStatus.OFFERED || expires == null || !expires.isAfter(now))
            throw invalid("Batch offer đã hết hạn hoặc không còn hợp lệ");
        return Accept.ACCEPT;
    }
    public static void requireUniqueOrders(List<Long> orders) {
        if (orders.stream().distinct().count() != orders.size()) throw invalid("Batch không được chứa duplicate order");
    }
    public static void requireOffered(DeliveryStatus status, Long shipper, Long offered) {
        if (status != DeliveryStatus.WAIT_SHIPPER_CONFIRM || !shipper.equals(offered))
            throw invalid("Batch có delivery không còn ở trạng thái offer");
    }
    public static boolean updatePosition(Double lat, Double lng) { return lat != null && lng != null; }
    public static void requireViewActor(boolean role, Long shipper) {
        if (!role || shipper == null || shipper <= 0) throw denied("Chỉ shipper mới có thể xem batch offer");
    }
    public static boolean retire(boolean present, DeliveryBatchStatus status) {
        return present && status == DeliveryBatchStatus.OFFERED;
    }
    public static boolean belongsToBatch(UUID deliveryBatch, UUID batch) {
        return deliveryBatch != null && deliveryBatch.equals(batch);
    }
    public static void requireRejectActor(boolean role, Long shipper, UUID batch) {
        if (!role || shipper == null || shipper <= 0 || batch == null) throw denied("Chỉ shipper mới có thể từ chối batch");
    }
    public static void requireRejectReason(String reason) {
        if (reason == null || reason.isBlank()) throw invalid("Batch reject reason is required");
    }
    public static void requireCancelRequest(UUID batch, Long shipper) {
        if (batch == null || shipper == null || shipper <= 0) throw invalid("Batch and shipper are required");
    }
    public static void requireAccepted(DeliveryBatchStatus status) {
        if (status != DeliveryBatchStatus.ACCEPTED) throw invalid("Chỉ có thể huỷ batch trước khi pickup");
    }
    public record Assignment(DeliveryStatus status, Long shipper) {}
    public static void requireCancellable(List<Assignment> deliveries, Long shipper) {
        if (deliveries.isEmpty() || deliveries.stream().anyMatch(delivery ->
                delivery.status() != DeliveryStatus.ASSIGNED || !shipper.equals(delivery.shipper())))
            throw invalid("Batch chỉ có thể huỷ trước khi pickup toàn bộ item");
    }
    public static String cancellationReason(String reason) {
        return reason == null || reason.isBlank() ? "Batch cancelled by shipper" : reason;
    }
}
