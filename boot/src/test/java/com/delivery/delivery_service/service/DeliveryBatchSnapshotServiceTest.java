package com.delivery.delivery_service.service;

import com.delivery.delivery_service.dto.response.DeliveryResponse;
import com.delivery.delivery_service.entity.Delivery;
import com.delivery.delivery_service.entity.DeliveryBatch;
import com.delivery.delivery_service.entity.DeliveryBatchItem;
import com.delivery.delivery_service.entity.DeliveryBatchItemStatus;
import com.delivery.delivery_service.entity.DeliveryBatchStatus;
import com.delivery.delivery_service.mapper.DeliveryMapper;
import com.delivery.delivery_service.repository.DeliveryBatchItemRepository;
import com.delivery.delivery_service.repository.DeliveryBatchRepository;
import com.delivery.delivery_service.repository.DeliveryRepository;
import com.delivery.delivery_service.repository.ShipperIdentityProjectionRepository;
import com.delivery.delivery_service.entity.ShipperIdentityProjection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryBatchSnapshotServiceTest {

    @Mock DeliveryBatchRepository batches;
    @Mock DeliveryBatchItemRepository items;
    @Mock DeliveryRepository deliveries;
    @Mock DeliveryMapper mapper;
    @Mock ShipperIdentityProjectionRepository projections;

    @Test
    void returnsOnlyAnOwnedSnapshotWithAuthoritativeSequences() {
        UUID batchId = UUID.randomUUID();
        DeliveryBatch batch = batch(batchId, 7L);
        Delivery first = delivery(batchId, 1L, 101L, 7L);
        Delivery second = delivery(batchId, 2L, 102L, 7L);
        List<DeliveryBatchItem> route = List.of(item(batchId, 1L, 0, 2), item(batchId, 2L, 1, 3));
        when(batches.findById(batchId)).thenReturn(Optional.of(batch));
        when(items.findByBatchIdOrderByPickupSequenceAsc(batchId)).thenReturn(route);
        when(deliveries.findById(1L)).thenReturn(Optional.of(first));
        when(deliveries.findById(2L)).thenReturn(Optional.of(second));
        when(mapper.deliveryToDeliveryResponse(first)).thenReturn(new DeliveryResponse());
        when(mapper.deliveryToDeliveryResponse(second)).thenReturn(new DeliveryResponse());

        DeliveryBatchSnapshotService service = service();
        var result = service.getSnapshot(batchId, 1007L, 107L, "SHIPPER");

        assertThat(result.getBatchId()).isEqualTo(batchId);
        assertThat(result.getItems()).extracting("pickupSequence").containsExactly(0, 1);
        assertThat(result.getItems()).extracting("dropoffSequence").containsExactly(2, 3);
    }

    @Test
    void rejectsAProjectionMissingOrMalformedRouteBeforeReturningState() {
        UUID batchId = UUID.randomUUID();
        DeliveryBatch batch = batch(batchId, 7L);
        when(batches.findById(batchId)).thenReturn(Optional.of(batch));
        when(items.findByBatchIdOrderByPickupSequenceAsc(batchId))
                .thenReturn(List.of(item(batchId, 1L, 0, 0)));

        assertThrows(RuntimeException.class,
                () -> service().getSnapshot(batchId, 1007L, 107L, "SHIPPER"));
    }

    private DeliveryBatchSnapshotService service() {
        ShipperIdentityProjection mapping = new ShipperIdentityProjection();
        mapping.setPrincipalId(1007L);
        mapping.setLegacyUserId(107L);
        mapping.setShipperId(7L);
        when(projections.findById(1007L)).thenReturn(Optional.of(mapping));
        DeliveryBatchSnapshotService service = new DeliveryBatchSnapshotService(
                batches, items, deliveries, mapper,
                ShipperIdentityResolver.compatibility(projections, null, false));
        ReflectionTestUtils.setField(service, "batchEnabled", true);
        return service;
    }

    private DeliveryBatch batch(UUID id, Long shipperId) {
        DeliveryBatch batch = new DeliveryBatch();
        batch.setBatchId(id);
        batch.setShipperId(shipperId);
        batch.setStatus(DeliveryBatchStatus.ACCEPTED);
        batch.setRouteVersion(1);
        batch.setTotalCodAmount(new BigDecimal("200000"));
        return batch;
    }

    private DeliveryBatchItem item(UUID batchId, Long deliveryId, int pickup, int dropoff) {
        DeliveryBatchItem item = new DeliveryBatchItem();
        item.setBatchId(batchId);
        item.setDeliveryId(deliveryId);
        item.setPickupSequence(pickup);
        item.setDropoffSequence(dropoff);
        item.setItemStatus(DeliveryBatchItemStatus.ACCEPTED);
        return item;
    }

    private Delivery delivery(UUID batchId, Long id, Long orderId, Long shipperId) {
        Delivery delivery = new Delivery();
        delivery.setId(id);
        delivery.setOrderId(orderId);
        delivery.setBatchId(batchId);
        delivery.setShipperId(shipperId);
        delivery.setStatus(com.delivery.delivery_service.entity.DeliveryStatus.ASSIGNED);
        return delivery;
    }
}
