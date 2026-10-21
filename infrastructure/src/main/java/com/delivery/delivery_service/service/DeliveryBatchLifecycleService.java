package com.delivery.delivery_service.service;

import com.delivery.delivery.domain.BatchDecisionPolicy;
import static com.delivery.delivery_service.service.DeliveryPolicyAdapter.*;

import com.delivery.delivery_service.common.constants.KafkaTopicConstants;
import com.delivery.delivery_service.common.constants.RoleConstants;
import com.delivery.delivery_service.entity.Delivery;
import com.delivery.delivery_service.entity.DeliveryBatch;
import com.delivery.delivery_service.entity.DeliveryBatchItem;
import com.delivery.delivery_service.entity.DeliveryBatchItemStatus;
import com.delivery.delivery_service.entity.DeliveryBatchStatus;
import com.delivery.delivery_service.entity.DeliveryStatus;
import com.delivery.delivery_service.exception.InvalidStatusException;
import com.delivery.delivery_service.repository.DeliveryBatchItemRepository;
import com.delivery.delivery_service.repository.DeliveryBatchRepository;
import com.delivery.delivery_service.repository.DeliveryRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Retires expired/rejected offers and emits the durable Match/Settlement release intent. */
@Service
public class DeliveryBatchLifecycleService {
    @Value("${delivery.batch.enabled:false}")
    private boolean batchEnabled;
    private final DeliveryBatchRepository batchRepository;
    private final DeliveryBatchItemRepository itemRepository;
    private final DeliveryRepository deliveryRepository;
    private final OutboxService outboxService;
    private final DeliveryEventPublisher eventPublisher;
    private final ShipperIdentityResolver shipperIdentityResolver;

    /** Compatibility constructor for direct unit fixtures. */
    public DeliveryBatchLifecycleService(DeliveryBatchRepository batchRepository,
                                         DeliveryBatchItemRepository itemRepository,
                                         DeliveryRepository deliveryRepository,
                                         OutboxService outboxService,
                                         DeliveryEventPublisher eventPublisher) {
        this(batchRepository, itemRepository, deliveryRepository, outboxService, eventPublisher, null);
    }

    @Autowired
    public DeliveryBatchLifecycleService(DeliveryBatchRepository batchRepository,
                                         DeliveryBatchItemRepository itemRepository,
                                         DeliveryRepository deliveryRepository,
                                         OutboxService outboxService,
                                         DeliveryEventPublisher eventPublisher,
                                         ShipperIdentityResolver shipperIdentityResolver) {
        this.batchRepository = batchRepository;
        this.itemRepository = itemRepository;
        this.deliveryRepository = deliveryRepository;
        this.outboxService = outboxService;
        this.eventPublisher = eventPublisher;
        this.shipperIdentityResolver = shipperIdentityResolver;
    }

    @Transactional
    public void reject(UUID batchId, Long principalId, Long legacyUserId, String role, String reason) {
        if (shipperIdentityResolver == null) {
            throw new com.delivery.delivery_service.exception.AccessDeniedException("Shipper identity resolver is unavailable");
        }
        reject(batchId, shipperIdentityResolver.resolveShipperId(principalId, legacyUserId, role), role, reason);
    }

    @Scheduled(fixedDelayString = "${delivery.batch.expiry-scan-ms:1000}")
    @Transactional
    public void expireOffers() {
        if (!batchEnabled) return;
        batchRepository.findExpiredOffersForUpdate(LocalDateTime.now(), PageRequest.of(0, 100))
                .forEach(this::retire);
    }

    @Transactional
    public void retire(DeliveryBatch batch) {
        if (!BatchDecisionPolicy.retire(batch != null, batch == null ? null : domain(batch.getStatus()))) return;
        List<DeliveryBatchItem> items = itemRepository.findByBatchIdOrderByPickupSequenceAsc(batch.getBatchId());
        List<Long> deliveryIds = new java.util.ArrayList<>();
        List<String> sessions = new java.util.ArrayList<>();
        for (DeliveryBatchItem item : items) {
            Delivery delivery = deliveryRepository.findByIdForUpdate(item.getDeliveryId())
                    .orElseThrow(() -> new InvalidStatusException("Batch delivery disappeared"));
            deliveryIds.add(delivery.getId());
            sessions.add(delivery.getOfferedMatchingSessionId() == null ? "" : delivery.getOfferedMatchingSessionId());
            if (BatchDecisionPolicy.belongsToBatch(delivery.getBatchId(), batch.getBatchId())) {
                delivery.setBatchId(null);
                delivery.setBatchSequence(null);
                delivery.setOfferedShipperId(null);
                delivery.setOfferExpiresAt(null);
                delivery.setOfferedMatchingSessionId(null);
                delivery.setStatus(DeliveryStatus.FINDING_SHIPPER);
                delivery.setUpdatedAt(LocalDateTime.now());
                deliveryRepository.save(delivery);
                publishRejected(delivery, batch.getShipperId(), "Batch offer expired or was rejected",
                        batchWaveForNext(batch));
            }
            item.setItemStatus(DeliveryBatchItemStatus.CANCELLED);
            item.setUpdatedAt(LocalDateTime.now());
        }
        itemRepository.saveAll(items);
        batch.setStatus(DeliveryBatchStatus.RETIRED);
        batch.setUpdatedAt(LocalDateTime.now());
        batchRepository.saveAndFlush(batch);
        publishRelease(batch, deliveryIds, sessions);
    }

    @Transactional
    public void reject(UUID batchId, Long shipperId, String role, String reason) {
        policy(() -> BatchDecisionPolicy.requireEnabled(batchEnabled));
        policy(() -> BatchDecisionPolicy.requireRejectActor(RoleConstants.SHIPPER.equals(role), shipperId, batchId));
        policy(() -> BatchDecisionPolicy.requireRejectReason(reason));
        DeliveryBatch batch = batchRepository.findByIdForUpdate(batchId)
                .orElseThrow(() -> new InvalidStatusException("Không tìm thấy batch offer"));
        policy(() -> BatchDecisionPolicy.requireOwner(shipperId, batch.getShipperId()));
        if (!BatchDecisionPolicy.retire(true, domain(batch.getStatus()))) return;
        retire(batch);
    }

    /**
     * A post-accept cancellation is atomic at batch scope. Partial cancellation
     * would clear the shipper reservation while the remaining delivery rows are
     * still assigned to the same shipper.
     */
    @Transactional
    public Delivery cancelAcceptedBatch(UUID batchId, Long shipperId, String reason) {
        policy(() -> BatchDecisionPolicy.requireEnabled(batchEnabled));
        policy(() -> BatchDecisionPolicy.requireCancelRequest(batchId, shipperId));
        DeliveryBatch batch = batchRepository.findByIdForUpdate(batchId)
                .orElseThrow(() -> new InvalidStatusException("Không tìm thấy batch đang hoạt động"));
        policy(() -> BatchDecisionPolicy.requireOwner(shipperId, batch.getShipperId()));
        policy(() -> BatchDecisionPolicy.requireAccepted(domain(batch.getStatus())));

        List<DeliveryBatchItem> items = itemRepository.findByBatchIdOrderByPickupSequenceAsc(batchId);
        List<Delivery> deliveries = items.stream().map(item -> deliveryRepository.findByIdForUpdate(item.getDeliveryId())
                .orElseThrow(() -> new InvalidStatusException("Batch delivery không tồn tại"))).toList();
        policy(() -> BatchDecisionPolicy.requireCancellable(deliveries.stream().map(delivery ->
                new BatchDecisionPolicy.Assignment(domain(delivery.getStatus()), delivery.getShipperId())).toList(), shipperId));

        LocalDateTime now = LocalDateTime.now();
        List<Long> deliveryIds = new java.util.ArrayList<>();
        List<String> sessions = new java.util.ArrayList<>();
        for (Delivery delivery : deliveries) {
            deliveryIds.add(delivery.getId());
            sessions.add(delivery.getOfferedMatchingSessionId() == null ? "" : delivery.getOfferedMatchingSessionId());
            delivery.setShipperId(null);
            delivery.setBatchId(null);
            delivery.setBatchSequence(null);
            delivery.setOfferedShipperId(shipperId);
            delivery.setOfferExpiresAt(null);
            delivery.setStatus(DeliveryStatus.FINDING_SHIPPER);
            delivery.setRejectReason(BatchDecisionPolicy.cancellationReason(reason));
            delivery.setUpdatedAt(now);
            deliveryRepository.save(delivery);
            eventPublisher.publishShipperStatusChange(shipperId, "AVAILABLE", delivery.getId(),
                    delivery.getOrderId(), batchId, delivery.getSimulationContext());
            publishRejected(delivery, shipperId, delivery.getRejectReason(), batchWaveForNext(batch));
        }
        items.forEach(item -> {
            item.setItemStatus(DeliveryBatchItemStatus.CANCELLED);
            item.setUpdatedAt(now);
        });
        itemRepository.saveAll(items);
        batch.setStatus(DeliveryBatchStatus.CANCELLED);
        batch.setUpdatedAt(now);
        batchRepository.saveAndFlush(batch);
        publishRelease(batch, deliveryIds, sessions);
        return deliveries.get(0);
    }

    private void publishRelease(DeliveryBatch batch, List<Long> deliveryIds, List<String> sessions) {
        List<String> holdIds = batch.getCodHoldIds() == null || batch.getCodHoldIds().isBlank()
                ? List.of() : List.of(batch.getCodHoldIds().split(","));
        Map<String, Object> payload = new HashMap<>();
        payload.put("batchId", batch.getBatchId().toString());
        payload.put("holdIds", holdIds);
        payload.put("target", "RELEASED");
        payload.put("deliveryIds", deliveryIds);
        payload.put("matchingSessionIds", sessions);
        UUID eventId = UUID.nameUUIDFromBytes(("batch-hold:RELEASED:" + batch.getBatchId())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        outboxService.saveEvent(eventId, "DELIVERY_BATCH", batch.getBatchId().toString(),
                "BATCH_COD_HOLD_RELEASED", KafkaTopicConstants.BATCH_RELEASED_TOPIC,
                batch.getBatchId().toString(), payload);
    }

    private void publishRejected(Delivery delivery, Long shipperId, String reason, int nextWave) {
        Map<String, Object> event = new HashMap<>();
        event.put("orderId", delivery.getOrderId());
        event.put("deliveryId", delivery.getId());
        event.put("rejectedShipperId", shipperId);
        event.put("rejectReason", reason);
        event.put("pickupAddress", delivery.getPickupAddress());
        event.put("pickupLat", delivery.getPickupLat());
        event.put("pickupLng", delivery.getPickupLng());
        event.put("deliveryAddress", delivery.getDeliveryAddress());
        event.put("deliveryLat", delivery.getDeliveryLat());
        event.put("deliveryLng", delivery.getDeliveryLng());
        event.put("simulationContext", delivery.getSimulationContext());
        event.put("eventType", "SHIPPER_REJECTED");
        event.put("batchWave", nextWave);
        event.put("timestamp", System.currentTimeMillis());
        outboxService.saveEvent("DELIVERY", delivery.getId().toString(), "SHIPPER_REJECTED",
                KafkaTopicConstants.SHIPPER_REJECTED_TOPIC, delivery.getOrderId().toString(), event);
    }

    private int batchWaveForNext(DeliveryBatch batch) {
        return BatchDecisionPolicy.nextWave(batch.getWaveNumber());
    }
}
