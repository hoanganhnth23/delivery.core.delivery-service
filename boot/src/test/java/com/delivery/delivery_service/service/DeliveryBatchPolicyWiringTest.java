package com.delivery.delivery_service.service;

import com.delivery.delivery_service.dto.event.ShipperFoundEvent;
import com.delivery.delivery_service.dto.request.AcceptBatchRequest;
import com.delivery.delivery_service.entity.*;
import com.delivery.delivery_service.exception.AccessDeniedException;
import com.delivery.delivery_service.exception.InvalidStatusException;
import com.delivery.delivery_service.mapper.DeliveryMapper;
import com.delivery.delivery_service.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Adapter regression proof: check order, lock/write order, replay and durable event identities. */
class DeliveryBatchPolicyWiringTest {
    final DeliveryRepository deliveries = mock(DeliveryRepository.class);
    final DeliveryBatchRepository batches = mock(DeliveryBatchRepository.class);
    final DeliveryBatchItemRepository items = mock(DeliveryBatchItemRepository.class);
    final DeliveryEventPublisher publisher = mock(DeliveryEventPublisher.class);
    final OutboxService outbox = mock(OutboxService.class);
    final DeliveryMapper mapper = mock(DeliveryMapper.class);
    final UUID id = UUID.randomUUID();
    DeliveryBatch batch(DeliveryBatchStatus status) {
        DeliveryBatch batch = new DeliveryBatch(); batch.setBatchId(id); batch.setShipperId(7L);
        batch.setStatus(status); batch.setOfferExpiresAt(LocalDateTime.now().plusMinutes(5));
        batch.setCodHoldIds(UUID.randomUUID().toString()); return batch;
    }
    Delivery delivery(DeliveryStatus status) {
        Delivery delivery = new Delivery(); delivery.setId(1L); delivery.setOrderId(101L);
        delivery.setBatchId(id); delivery.setShipperId(7L); delivery.setOfferedShipperId(7L);
        delivery.setOfferedMatchingSessionId("session"); delivery.setStatus(status); return delivery;
    }
    DeliveryBatchItem item(DeliveryBatchItemStatus status) {
        DeliveryBatchItem item = new DeliveryBatchItem(); item.setBatchId(id); item.setDeliveryId(1L);
        item.setPickupSequence(0); item.setDropoffSequence(1); item.setItemStatus(status); return item;
    }
    DeliveryBatchOfferService offers() {
        var service = new DeliveryBatchOfferService(deliveries,batches,items,outbox,publisher);
        ReflectionTestUtils.setField(service,"batchEnabled",true); return service;
    }
    DeliveryBatchAcceptanceService acceptance() {
        var service = new DeliveryBatchAcceptanceService(batches,items,deliveries,mapper,publisher,outbox);
        ReflectionTestUtils.setField(service,"batchEnabled",true); return service;
    }
    DeliveryBatchLifecycleService lifecycle() {
        var service = new DeliveryBatchLifecycleService(batches,items,deliveries,outbox,publisher);
        ReflectionTestUtils.setField(service,"batchEnabled",true); return service;
    }
    ShipperFoundEvent event() {
        ShipperFoundEvent event = new ShipperFoundEvent(); event.setBatchOffer(true); event.setBatchId(id);
        var shipper = new ShipperFoundEvent.ShipperMatchResult(); shipper.setShipperId(7L);
        event.setAvailableShippers(List.of(shipper)); event.setCodHoldIds(List.of(UUID.randomUUID()));
        event.setBatchItems(List.of(new ShipperFoundEvent.BatchItem(1L,101L,0,1,BigDecimal.TEN,UUID.randomUUID())));
        return event;
    }
    AcceptBatchRequest request() { var request = new AcceptBatchRequest(); request.setBatchId(id); return request; }
    @Test void disabledAndInvalidActorsFailBeforeAnyLocks() {
        var offer = offers(); ReflectionTestUtils.setField(offer,"batchEnabled",false);
        assertEquals("Delivery batch dispatch is disabled",assertThrows(InvalidStatusException.class,()->offer.apply(null)).getMessage());
        assertEquals("Chỉ shipper mới có thể nhận batch",assertThrows(AccessDeniedException.class,()->acceptance().accept(null,7L,"USER")).getMessage());
        assertEquals("Chỉ shipper mới có thể từ chối batch",assertThrows(AccessDeniedException.class,()->lifecycle().reject(null,7L,"USER",null)).getMessage());
        verifyNoInteractions(batches,deliveries,items,outbox,publisher);
    }
    @Test void offerLocksBatchThenDeliveryAndPublishesAfterPersisting() {
        Delivery delivery = delivery(DeliveryStatus.FINDING_SHIPPER); delivery.setBatchId(null);
        when(batches.findByIdForUpdate(id)).thenReturn(Optional.empty());
        when(deliveries.findByIdForUpdate(1L)).thenReturn(Optional.of(delivery));
        offers().apply(event());
        assertEquals(DeliveryStatus.WAIT_SHIPPER_CONFIRM,delivery.getStatus()); assertEquals(id,delivery.getBatchId());
        var order = inOrder(batches,deliveries,items,outbox,publisher);
        order.verify(batches).findByIdForUpdate(id); order.verify(batches).save(any());
        order.verify(deliveries).findByIdForUpdate(1L); order.verify(deliveries).save(delivery);
        order.verify(items).save(any()); order.verify(batches).saveAndFlush(any());
        order.verify(outbox).saveEvent(eq(UUID.nameUUIDFromBytes(("batch-offered:"+id).getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                eq("DELIVERY_BATCH"),eq(id.toString()),eq("BATCH_SHIPPER_OFFERED"),anyString(),eq(id.toString()),any());
        order.verify(publisher).publishOfferPersisted(any());
    }
    @Test void offerReplayAcknowledgesWithoutChangingItemsOrOutbox() {
        when(batches.findByIdForUpdate(id)).thenReturn(Optional.of(batch(DeliveryBatchStatus.OFFERED)));
        offers().apply(event()); verify(publisher).publishOfferPersisted(any());
        verifyNoInteractions(deliveries,items,outbox);
    }
    @Test void offerOrderMismatchPrecedesAvailabilityFailure() {
        var delivery = delivery(DeliveryStatus.DELIVERED); delivery.setOrderId(999L);
        when(batches.findByIdForUpdate(id)).thenReturn(Optional.empty());
        when(deliveries.findByIdForUpdate(1L)).thenReturn(Optional.of(delivery));
        assertEquals("Batch order does not match delivery",assertThrows(InvalidStatusException.class,()->offers().apply(event())).getMessage());
        verify(deliveries,never()).save(any()); verifyNoInteractions(items,outbox,publisher);
    }
    @Test void acceptanceWritesEveryProjectionBeforeCommittedHoldEvent() {
        var batch = batch(DeliveryBatchStatus.OFFERED); var item = item(DeliveryBatchItemStatus.OFFERED);
        var delivery = delivery(DeliveryStatus.WAIT_SHIPPER_CONFIRM);
        when(batches.findByIdForUpdate(id)).thenReturn(Optional.of(batch));
        when(items.findByBatchIdOrderByPickupSequenceAsc(id)).thenReturn(List.of(item));
        when(deliveries.findByIdForUpdate(1L)).thenReturn(Optional.of(delivery));
        acceptance().accept(request(),7L,"SHIPPER");
        assertEquals(DeliveryStatus.ASSIGNED,delivery.getStatus()); assertEquals(DeliveryBatchStatus.ACCEPTED,batch.getStatus());
        assertEquals(DeliveryBatchItemStatus.ACCEPTED,item.getItemStatus());
        var order = inOrder(batches,items,deliveries,publisher,outbox);
        order.verify(batches).findByIdForUpdate(id); order.verify(items).findByBatchIdOrderByPickupSequenceAsc(id);
        order.verify(deliveries).findByIdForUpdate(1L); order.verify(deliveries).save(delivery);
        order.verify(publisher).publishShipperStatusChange(eq(7L),eq("BUSY"),eq(1L),eq(101L),eq(id),any());
        order.verify(publisher).publishShipperAcceptedEvent(any()); order.verify(items).saveAll(List.of(item));
        order.verify(batches).saveAndFlush(batch);
        order.verify(outbox).saveEvent(any(UUID.class),eq("DELIVERY_BATCH"),eq(id.toString()),eq("BATCH_COD_HOLD_COMMITTED"),anyString(),eq(id.toString()),any());
    }
    @Test void acceptedReplayDoesNotRequireDeadlineOrLockDelivery() {
        var batch = batch(DeliveryBatchStatus.ACCEPTED); batch.setOfferExpiresAt(null);
        when(batches.findByIdForUpdate(id)).thenReturn(Optional.of(batch));
        when(items.findByBatchIdOrderByPickupSequenceAsc(id)).thenReturn(List.of(item(DeliveryBatchItemStatus.ACCEPTED)));
        when(deliveries.findById(1L)).thenReturn(Optional.of(delivery(DeliveryStatus.ASSIGNED)));
        acceptance().accept(request(),7L,"SHIPPER");
        verify(deliveries,never()).findByIdForUpdate(any()); verifyNoInteractions(publisher,outbox);
    }
    @Test void retirementPublishesRejectionBeforeReleaseWithOriginalReason() {
        var batch = batch(DeliveryBatchStatus.OFFERED); var delivery = delivery(DeliveryStatus.WAIT_SHIPPER_CONFIRM);
        var item = item(DeliveryBatchItemStatus.OFFERED);
        when(batches.findByIdForUpdate(id)).thenReturn(Optional.of(batch));
        when(items.findByBatchIdOrderByPickupSequenceAsc(id)).thenReturn(List.of(item));
        when(deliveries.findByIdForUpdate(1L)).thenReturn(Optional.of(delivery));
        lifecycle().reject(id,7L,"SHIPPER","caller reason");
        assertNull(delivery.getBatchId()); assertEquals(DeliveryStatus.FINDING_SHIPPER,delivery.getStatus());
        assertEquals(DeliveryBatchStatus.RETIRED,batch.getStatus());
        var order = inOrder(batches,items,deliveries,outbox);
        order.verify(batches).findByIdForUpdate(id); order.verify(items).findByBatchIdOrderByPickupSequenceAsc(id);
        order.verify(deliveries).findByIdForUpdate(1L); order.verify(deliveries).save(delivery);
        order.verify(outbox).saveEvent(eq("DELIVERY"),eq("1"),eq("SHIPPER_REJECTED"),anyString(),eq("101"),argThat(payload ->
                "Batch offer expired or was rejected".equals(((java.util.Map<?,?>)payload).get("rejectReason"))));
        order.verify(items).saveAll(List.of(item)); order.verify(batches).saveAndFlush(batch);
        order.verify(outbox).saveEvent(any(UUID.class),eq("DELIVERY_BATCH"),eq(id.toString()),eq("BATCH_COD_HOLD_RELEASED"),anyString(),eq(id.toString()),any());
    }
    @Test void cancellationChecksAllItemsBeforeMutatingAnyDelivery() {
        var batch = batch(DeliveryBatchStatus.ACCEPTED); var delivery = delivery(DeliveryStatus.PICKED_UP);
        when(batches.findByIdForUpdate(id)).thenReturn(Optional.of(batch));
        when(items.findByBatchIdOrderByPickupSequenceAsc(id)).thenReturn(List.of(item(DeliveryBatchItemStatus.PICKED_UP)));
        when(deliveries.findByIdForUpdate(1L)).thenReturn(Optional.of(delivery));
        assertEquals("Batch chỉ có thể huỷ trước khi pickup toàn bộ item",assertThrows(InvalidStatusException.class,()->lifecycle().cancelAcceptedBatch(id,7L,null)).getMessage());
        verify(deliveries,never()).save(any()); verifyNoInteractions(publisher,outbox);
    }
    @Test void returnedItemCompletesBatchExactlyOnceAndKeepsEventIdentity() {
        var batch = batch(DeliveryBatchStatus.DELIVERING); var delivery = delivery(DeliveryStatus.RETURNED);
        var item = item(DeliveryBatchItemStatus.RETURNING);
        when(batches.findByIdForUpdate(id)).thenReturn(Optional.of(batch));
        when(items.findByBatchIdAndDeliveryIdForUpdate(id,1L)).thenReturn(Optional.of(item));
        when(items.findByBatchIdOrderByPickupSequenceAsc(id)).thenReturn(List.of(item));
        when(deliveries.findById(1L)).thenReturn(Optional.of(delivery));
        var progress = new DeliveryBatchProgressService(batches,items,deliveries,outbox);
        assertFalse(progress.applyExceptionReturn(delivery,false));
        assertEquals(DeliveryBatchStatus.DELIVERING,batch.getStatus()); verifyNoInteractions(outbox);
        assertTrue(progress.applyExceptionReturn(delivery,true)); assertTrue(progress.applyExceptionReturn(delivery,true));
        assertEquals(DeliveryBatchStatus.COMPLETED,batch.getStatus());
        verify(outbox,times(1)).saveEvent(eq(UUID.nameUUIDFromBytes(("batch-completed:"+id).getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                eq("DELIVERY_BATCH"),eq(id.toString()),eq("BATCH_COMPLETED"),anyString(),eq(id.toString()),any());
    }
    @Test void progressLocksBatchBeforeItemAndSavesItemBeforeBatch() {
        var batch = batch(DeliveryBatchStatus.ACCEPTED); var delivery = delivery(DeliveryStatus.PICKED_UP);
        var item = item(DeliveryBatchItemStatus.ACCEPTED);
        when(batches.findByIdForUpdate(id)).thenReturn(Optional.of(batch));
        when(items.findByBatchIdAndDeliveryIdForUpdate(id,1L)).thenReturn(Optional.of(item));
        when(items.findByBatchIdOrderByPickupSequenceAsc(id)).thenReturn(List.of(item));
        assertFalse(new DeliveryBatchProgressService(batches,items,deliveries,outbox).apply(delivery,DeliveryStatus.PICKED_UP));
        assertEquals(DeliveryBatchStatus.PICKED_UP,batch.getStatus());
        var order=inOrder(batches,items); order.verify(batches).findByIdForUpdate(id);
        order.verify(items).findByBatchIdAndDeliveryIdForUpdate(id,1L); order.verify(items).save(item);
        order.verify(items).findByBatchIdOrderByPickupSequenceAsc(id); order.verify(batches).save(batch); verifyNoInteractions(outbox);
    }
}
