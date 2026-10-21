package com.delivery.delivery.domain;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import static com.delivery.delivery.domain.BatchDecisionPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class BatchDecisionPolicyTest {
    private static final UUID ID = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.of(2026,10,5,12,0);
    private static void invalid(String message, Runnable rule) { refused(OfferDecisionRejected.Kind.INVALID_STATUS,message,rule); }
    private static void denied(String message, Runnable rule) { refused(OfferDecisionRejected.Kind.ACCESS_DENIED,message,rule); }
    private static void refused(OfferDecisionRejected.Kind kind,String message,Runnable rule) {
        var failure=assertThrows(OfferDecisionRejected.class,rule::run);
        assertEquals(kind,failure.kind()); assertEquals(message,failure.getMessage());
    }
    @Test void envelopeKeepsShortCircuitOrder() {
        requireEnabled(true); invalid("Delivery batch dispatch is disabled", () -> requireEnabled(false));
        requireOffer(true,true,ID,3,1,()->7L);
        String message="Invalid batch shipper offer event";
        invalid(message,()->requireOffer(false,true,ID,1,1,()->{fail("must stay lazy");return null;}));
        for(Boolean flag:new Boolean[]{null,false}) invalid(message,()->requireOffer(true,flag,ID,1,1,()->7L));
        invalid(message,()->requireOffer(true,true,null,1,1,()->7L));
        for(Integer count:new Integer[]{null,0,4}) invalid(message,()->requireOffer(true,true,ID,count,1,()->7L));
        for(Integer count:new Integer[]{null,0,2}) invalid(message,()->requireOffer(true,true,ID,1,count,()->7L));
        invalid(message,()->requireOffer(true,true,ID,1,1,()->null));
        requireSession(true,ID);
        invalid("Batch delivery IDs must be unique and complete",()->requireSession(false,ID));
        invalid("Batch delivery IDs must be unique and complete",()->requireSession(true,null));
    }
    @Test void deadlineReplayHoldsAndAmounts() {
        assertEquals(NOW.plusSeconds(180),expiresAt(null,null,NOW));
        assertEquals(NOW.plusSeconds(1),expiresAt(NOW,-1,NOW));
        assertEquals(NOW.plusSeconds(180),expiresAt(NOW,999,NOW));
        invalid("Batch shipper offer already expired",()->expiresAt(NOW.minusSeconds(2),2,NOW));
        invalid("Batch shipper offer already expired",()->expiresAt(NOW.minusSeconds(3),2,NOW));
        requireReplay(7L,7L,DeliveryBatchStatus.OFFERED);
        invalid("Batch offer replay conflicts with existing batch",()->requireReplay(7L,8L,DeliveryBatchStatus.OFFERED));
        invalid("Batch offer replay conflicts with existing batch",()->requireReplay(7L,7L,DeliveryBatchStatus.ACCEPTED));
        assertEquals(0,wave(null)); assertEquals(0,wave(-1)); assertEquals(2,wave(2));
        assertEquals(1,nextWave(-1)); assertEquals(3,nextWave(2));
        requireHolds(2,2);
        invalid("Batch COD holds are incomplete",()->requireHolds(null,2));
        invalid("Batch COD holds are incomplete",()->requireHolds(1,2));
        requireOrder(1L,1L); invalid("Batch order does not match delivery",()->requireOrder(1L,2L));
        for(DeliveryStatus status:DeliveryStatus.values()) {
            if(status==DeliveryStatus.FINDING_SHIPPER || status==DeliveryStatus.WAIT_SHIPPER_CONFIRM) requireAvailable(null,status);
            else invalid("Delivery is not available for batch assignment",()->requireAvailable(null,status));
        }
        invalid("Delivery is not available for batch assignment",()->requireAvailable(ID,DeliveryStatus.FINDING_SHIPPER));
        assertEquals(BigDecimal.ZERO,cod(null)); assertEquals(BigDecimal.ONE,cod(BigDecimal.ONE));
    }
    @Test void acceptAndViewGuardsAndReplay() {
        requireAcceptActor(true); denied("Chỉ shipper mới có thể nhận batch",()->requireAcceptActor(false));
        requireAcceptRequest(true,ID,1L);
        invalid("Batch ID and shipper are required",()->requireAcceptRequest(false,ID,1L));
        invalid("Batch ID and shipper are required",()->requireAcceptRequest(true,null,1L));
        for(Long shipper:new Long[]{null,0L,-1L}) invalid("Batch ID and shipper are required",()->requireAcceptRequest(true,ID,shipper));
        requireOwner(1L,1L); denied("Batch không thuộc shipper này",()->requireOwner(1L,null));
        assertEquals(Accept.REPLAY,onAccept(DeliveryBatchStatus.ACCEPTED,null,NOW));
        assertEquals(Accept.ACCEPT,onAccept(DeliveryBatchStatus.OFFERED,NOW.plusSeconds(1),NOW));
        for(DeliveryBatchStatus status:DeliveryBatchStatus.values()) if(status!=DeliveryBatchStatus.OFFERED && status!=DeliveryBatchStatus.ACCEPTED)
            invalid("Batch offer đã hết hạn hoặc không còn hợp lệ",()->onAccept(status,null,NOW));
        for(LocalDateTime deadline:new LocalDateTime[]{null,NOW,NOW.minusSeconds(1)})
            invalid("Batch offer đã hết hạn hoặc không còn hợp lệ",()->onAccept(DeliveryBatchStatus.OFFERED,deadline,NOW));
        requireUniqueOrders(List.of(1L,2L)); invalid("Batch không được chứa duplicate order",()->requireUniqueOrders(List.of(1L,1L)));
        requireOffered(DeliveryStatus.WAIT_SHIPPER_CONFIRM,1L,1L);
        invalid("Batch có delivery không còn ở trạng thái offer",()->requireOffered(DeliveryStatus.ASSIGNED,1L,1L));
        invalid("Batch có delivery không còn ở trạng thái offer",()->requireOffered(DeliveryStatus.WAIT_SHIPPER_CONFIRM,1L,2L));
        assertTrue(updatePosition(1.0,2.0)); assertFalse(updatePosition(null,2.0)); assertFalse(updatePosition(1.0,null));
        requireViewActor(true,1L); denied("Chỉ shipper mới có thể xem batch offer",()->requireViewActor(false,1L));
        for(Long shipper:new Long[]{null,0L,-1L}) denied("Chỉ shipper mới có thể xem batch offer",()->requireViewActor(true,shipper));
    }
    @Test void retirementAndCancellation() {
        assertFalse(retire(false,DeliveryBatchStatus.OFFERED)); assertFalse(retire(true,DeliveryBatchStatus.ACCEPTED)); assertTrue(retire(true,DeliveryBatchStatus.OFFERED));
        assertFalse(belongsToBatch(null,ID)); assertFalse(belongsToBatch(UUID.randomUUID(),ID)); assertTrue(belongsToBatch(ID,ID));
        requireRejectActor(true,1L,ID); denied("Chỉ shipper mới có thể từ chối batch",()->requireRejectActor(false,1L,ID));
        for(Long shipper:new Long[]{null,0L,-1L}) denied("Chỉ shipper mới có thể từ chối batch",()->requireRejectActor(true,shipper,ID));
        denied("Chỉ shipper mới có thể từ chối batch",()->requireRejectActor(true,1L,null));
        requireRejectReason(" reason ");
        for(String reason:new String[]{null,""," "}) invalid("Batch reject reason is required",()->requireRejectReason(reason));
        requireCancelRequest(ID,1L); invalid("Batch and shipper are required",()->requireCancelRequest(null,1L));
        for(Long shipper:new Long[]{null,0L,-1L}) invalid("Batch and shipper are required",()->requireCancelRequest(ID,shipper));
        requireAccepted(DeliveryBatchStatus.ACCEPTED); invalid("Chỉ có thể huỷ batch trước khi pickup",()->requireAccepted(DeliveryBatchStatus.PICKED_UP));
        requireCancellable(List.of(new Assignment(DeliveryStatus.ASSIGNED,1L)),1L);
        invalid("Batch chỉ có thể huỷ trước khi pickup toàn bộ item",()->requireCancellable(List.of(),1L));
        invalid("Batch chỉ có thể huỷ trước khi pickup toàn bộ item",()->requireCancellable(List.of(new Assignment(DeliveryStatus.PICKED_UP,1L)),1L));
        invalid("Batch chỉ có thể huỷ trước khi pickup toàn bộ item",()->requireCancellable(List.of(new Assignment(DeliveryStatus.ASSIGNED,2L)),1L));
        assertEquals("Batch cancelled by shipper",cancellationReason(null)); assertEquals("Batch cancelled by shipper",cancellationReason(" "));
        assertEquals(" reason ",cancellationReason(" reason "));
    }
}
