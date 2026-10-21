package com.delivery.delivery.domain;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import static com.delivery.delivery.domain.DeliveryExceptionStatus.*;
import static com.delivery.delivery.domain.DeliveryExceptionPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryExceptionPolicyTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 12, 0);
    private static void rejected(OfferDecisionRejected.Kind kind, String message, Runnable rule) {
        var failure = assertThrows(OfferDecisionRejected.class, rule::run);
        assertEquals(kind, failure.kind()); assertEquals(message, failure.getMessage());
    }
    private static void invalid(String message, Runnable rule) { rejected(OfferDecisionRejected.Kind.INVALID_STATUS, message, rule); }
    @Test void guardsAndNormalization() {
        assertFalse(releaseShipper(false, 1L)); assertFalse(releaseShipper(true, null)); assertTrue(releaseShipper(true, 1L));
        assertEquals(BigDecimal.ONE, shippingSnapshot(null, BigDecimal.ONE));
        assertEquals(BigDecimal.TEN, shippingSnapshot(BigDecimal.TEN, BigDecimal.ONE));
        requireEnabled(true);
        invalid("Luồng sự cố giao hàng chưa được bật", () -> requireEnabled(false));
        for (Long id : new Long[]{null, 0L, -1L}) invalid("Delivery ID is required", () -> requireDeliveryId(id));
        requireDeliveryId(1L);
        String message = "Lý do sự cố là bắt buộc và không quá 500 ký tự";
        for (String reason : new String[]{null, "", "  ", "x".repeat(501)}) invalid(message, () -> requireReason(reason));
        assertEquals("valid", requireReason(" valid "));
        assertEquals("x".repeat(500), requireReason("x".repeat(500)));
        for (DeliveryStatus status : DeliveryStatus.values()) {
            if (status == DeliveryStatus.PICKED_UP || status == DeliveryStatus.DELIVERING) requirePostPickup(status);
            else invalid("Chỉ có thể báo sự cố sau khi đã lấy hàng", () -> requirePostPickup(status));
        }
        requireAssignedShipper(7L, 7L); requireAssignedShipper(null, null);
        rejected(OfferDecisionRejected.Kind.ACCESS_DENIED, "Chỉ shipper được phân công mới có thể báo sự cố giao hàng", () -> requireAssignedShipper(7L, 8L));
        requireRestaurantOwner(true, 1L, 2L, 1L, 99L);
        requireRestaurantOwner(true, 1L, 2L, null, 2L);
        for (boolean role : new boolean[]{false, true}) rejected(OfferDecisionRejected.Kind.ACCESS_DENIED,
                "Chỉ chủ nhà hàng của đơn mới có thể xác nhận hoàn trả", () -> requireRestaurantOwner(role, 1L, 2L, 9L, 2L));
        assertEquals(NOW.plusMinutes(15), retryDeadline(NOW));
    }
    @Test void reportReplayAndEscalation() {
        assertEquals(Report.CREATE, onReport(false,null, null, null, "reason", NOW));
        for (DeliveryExceptionStatus status : DeliveryExceptionStatus.values()) {
            invalid("Sự cố giao hàng đã tồn tại với lý do khác", () -> onReport(true,status, "old", NOW, "new", NOW));
        }
        assertEquals(Report.RETURN_EXISTING, onReport(true,RETRY_AVAILABLE, "r", null, "r", NOW));
        assertEquals(Report.RETURN_EXISTING, onReport(true,RETRY_AVAILABLE, "r", NOW.plusSeconds(1), "r", NOW));
        for (LocalDateTime deadline : new LocalDateTime[]{NOW, NOW.minusSeconds(1)})
            assertEquals(Report.BEGIN_RETURN, onReport(true,RETRY_AVAILABLE, "r", deadline, "r", NOW));
        assertEquals(Report.BEGIN_RETURN, onReport(true,RETRY_USED, "r", NOW, "r", NOW));
        for (DeliveryExceptionStatus status : new DeliveryExceptionStatus[]{RETURNING, RETURNED})
            assertEquals(Report.RETURN_EXISTING, onReport(true,status, "r", NOW, "r", NOW));
        assertThrows(NullPointerException.class, () -> onReport(true,null,"r",null,"r",NOW));
        invalid(RESOLVED_MESSAGE, () -> onReport(true,RESOLVED, "r", NOW, "r", NOW));
    }
    @Test void retryPreservesReplayBeforeDeadlineAndStatusChecks() {
        assertEquals(Retry.REPLAY, onUseRetry(RETRY_USED, null, NOW));
        for (DeliveryExceptionStatus status : new DeliveryExceptionStatus[]{RETURNING, RETURNED})
            invalid("Đơn hàng đang hoặc đã được hoàn về nhà hàng", () -> onUseRetry(status, null, NOW));
        invalid(RESOLVED_MESSAGE, () -> onUseRetry(RESOLVED, null, NOW));
        assertEquals(Retry.USE_RETRY, onUseRetry(RETRY_AVAILABLE, NOW.plusSeconds(1), NOW));
        assertEquals(Retry.BEGIN_RETURN, onUseRetry(RETRY_AVAILABLE, NOW, NOW));
        assertEquals(Retry.BEGIN_RETURN, onUseRetry(RETRY_AVAILABLE, NOW.minusSeconds(1), NOW));
        assertThrows(NullPointerException.class, () -> onUseRetry(RETRY_AVAILABLE, null, NOW));
    }
    @Test void beginAndConfirmReturnStatuses() {
        for (DeliveryExceptionStatus status : DeliveryExceptionStatus.values()) {
            if (status == RESOLVED) invalid(RESOLVED_MESSAGE, () -> onBeginReturn(status));
            else assertEquals(status == RETURNING || status == RETURNED ? BeginReturn.ALREADY_RETURNING : BeginReturn.START, onBeginReturn(status));
            for (DeliveryStatus delivery : DeliveryStatus.values()) {
                if (status == RETURNED) assertEquals(ConfirmReturn.REPLAY, onConfirmReturn(status, delivery));
                else if (status == RETURNING && delivery == DeliveryStatus.RETURNING) assertEquals(ConfirmReturn.RETURN, onConfirmReturn(status, delivery));
                else invalid("Đơn hàng chưa ở trạng thái chờ xác nhận hoàn trả", () -> onConfirmReturn(status, delivery));
            }
        }
    }
    @Test void sweepAndSuccessfulDelivery() {
        for (DeliveryExceptionStatus status : DeliveryExceptionStatus.values()) {
            if (status != RETRY_AVAILABLE) assertEquals(Sweep.SKIP, onRetryWindowSweep(status, NOW, DeliveryStatus.DELIVERED, NOW));
            if (status == RETRY_AVAILABLE || status == RETRY_USED) assertEquals(Delivered.RESOLVE, onSuccessfulDelivery(status));
            else if (status == RETURNING || status == RETURNED) invalid("Không thể hoàn tất đơn đang trong luồng hoàn trả", () -> onSuccessfulDelivery(status));
            else assertEquals(Delivered.NONE, onSuccessfulDelivery(status));
        }
        assertEquals(Delivered.NONE, onSuccessfulDelivery(null));
        assertEquals(Sweep.SKIP, onRetryWindowSweep(RETRY_AVAILABLE, null, DeliveryStatus.DELIVERED, NOW));
        assertEquals(Sweep.SKIP, onRetryWindowSweep(RETRY_AVAILABLE, NOW.plusSeconds(1), DeliveryStatus.DELIVERED, NOW));
        assertEquals(Sweep.RESOLVE, onRetryWindowSweep(RETRY_AVAILABLE, NOW, DeliveryStatus.DELIVERED, NOW));
        assertEquals(Sweep.BEGIN_RETURN, onRetryWindowSweep(RETRY_AVAILABLE, NOW, DeliveryStatus.DELIVERING, NOW));
    }
    @Test void monetarySnapshotChecksEachMissingAndInvalidComponent() {
        BigDecimal zero = BigDecimal.ZERO, one = BigDecimal.ONE, neg = one.negate();
        BigDecimal[][] invalid = {{null,one,one},{one,null,one},{one,one,null},{neg,one,one},{one,neg,one},{one,one,zero},{one,one,neg},{zero,zero,one}};
        for (BigDecimal[] values : invalid) invalid("Sự cố giao hàng cần snapshot tiền tệ bất biến hợp lệ", () -> requireMoneySnapshot(values[0],values[1],values[2]));
        assertEquals(one, requireMoneySnapshot(one, one, one));
        assertEquals(zero, requireMoneySnapshot(zero, one, one));
    }
}
