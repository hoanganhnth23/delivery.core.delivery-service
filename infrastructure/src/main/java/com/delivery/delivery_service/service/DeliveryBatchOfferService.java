package com.delivery.delivery_service.service;

import com.delivery.delivery.domain.BatchDecisionPolicy;
import static com.delivery.delivery_service.service.DeliveryPolicyAdapter.*;

import com.delivery.delivery_service.common.constants.KafkaTopicConstants;
import com.delivery.delivery_service.dto.event.ShipperFoundEvent;
import com.delivery.delivery_service.dto.event.OfferPersistedEvent;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Durable, all-or-nothing persistence boundary for a proposed shipper batch. */
@Service
public class DeliveryBatchOfferService {

    @Value("${delivery.batch.enabled:false}")
    private boolean batchEnabled;

    private final DeliveryRepository deliveryRepository;
    private final DeliveryBatchRepository batchRepository;
    private final DeliveryBatchItemRepository itemRepository;
    private final OutboxService outboxService;
    private final DeliveryEventPublisher eventPublisher;
    private final Clock clock = Clock.systemDefaultZone();

    public DeliveryBatchOfferService(DeliveryRepository deliveryRepository,
                                     DeliveryBatchRepository batchRepository,
                                     DeliveryBatchItemRepository itemRepository,
                                     OutboxService outboxService,
                                     DeliveryEventPublisher eventPublisher) {
        this.deliveryRepository = deliveryRepository;
        this.batchRepository = batchRepository;
        this.itemRepository = itemRepository;
        this.outboxService = outboxService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void apply(ShipperFoundEvent event) {
        policy(() -> BatchDecisionPolicy.requireEnabled(batchEnabled));
        policy(() -> BatchDecisionPolicy.requireOffer(event != null, event == null ? null : event.getBatchOffer(),
                event == null ? null : event.getBatchId(),
                event == null || event.getBatchItems() == null ? null : event.getBatchItems().size(),
                event == null || event.getAvailableShippers() == null ? null : event.getAvailableShippers().size(),
                () -> event.getAvailableShippers().get(0).getShipperId()));
        for (ShipperFoundEvent.BatchItem item : event.getBatchItems()) {
            policy(() -> BatchDecisionPolicy.requireSession(item != null, item == null ? null : item.getMatchingSessionId()));
        }
        DeliveryBatchRouteValidator.validate(event.getBatchItems());
        List<ShipperFoundEvent.BatchItem> orderedItems = event.getBatchItems().stream()
                .sorted(Comparator.comparing(ShipperFoundEvent.BatchItem::getPickupSequence)
                        .thenComparing(ShipperFoundEvent.BatchItem::getDropoffSequence))
                .toList();
        Long shipperId = event.getAvailableShippers().get(0).getShipperId();
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expiresAt = decision(() -> BatchDecisionPolicy.expiresAt(
                event.getFoundAt(), event.getWaitingTimeoutSeconds(), now));

        DeliveryBatch batch = batchRepository.findByIdForUpdate(event.getBatchId()).orElse(null);
        if (batch != null) {
            DeliveryBatch existing = batch;
            policy(() -> BatchDecisionPolicy.requireReplay(shipperId, existing.getShipperId(), domain(existing.getStatus())));
            publishOfferPersisted(event, shipperId, expiresAt);
            return;
        }

        batch = new DeliveryBatch();
        batch.setBatchId(event.getBatchId());
        batch.setShipperId(shipperId);
        batch.setStatus(DeliveryBatchStatus.OFFERED);
        batch.setOfferExpiresAt(expiresAt);
        batch.setRouteVersion(1);
        batch.setTotalCodAmount(BigDecimal.ZERO);
        batch.setWaveNumber(BatchDecisionPolicy.wave(event.getBatchWave()));
        policy(() -> BatchDecisionPolicy.requireHolds(event.getCodHoldIds() == null ? null : event.getCodHoldIds().size(),
                event.getBatchItems().size()));
        batch.setCodHoldIds(event.getCodHoldIds().stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(",")));
        batch.setCreatedAt(now);
        batch.setUpdatedAt(now);
        batchRepository.save(batch);

        for (ShipperFoundEvent.BatchItem item : orderedItems) {
            Delivery delivery = deliveryRepository.findByIdForUpdate(item.getDeliveryId())
                    .orElseThrow(() -> new InvalidStatusException("Delivery is missing from batch"));
            policy(() -> BatchDecisionPolicy.requireOrder(item.getOrderId(), delivery.getOrderId()));
            policy(() -> BatchDecisionPolicy.requireAvailable(delivery.getBatchId(), domain(delivery.getStatus())));
            delivery.setBatchId(event.getBatchId());
            delivery.setBatchSequence(item.getPickupSequence());
            delivery.setOfferedShipperId(shipperId);
            delivery.setOfferExpiresAt(expiresAt);
            delivery.setOfferedMatchingSessionId(item.getMatchingSessionId() == null
                    ? event.getMatchingSessionId() : item.getMatchingSessionId().toString());
            delivery.setStatus(DeliveryStatus.WAIT_SHIPPER_CONFIRM);
            delivery.setUpdatedAt(now);
            deliveryRepository.save(delivery);

            DeliveryBatchItem batchItem = new DeliveryBatchItem();
            batchItem.setBatchId(event.getBatchId());
            batchItem.setDeliveryId(delivery.getId());
            batchItem.setPickupSequence(item.getPickupSequence());
            batchItem.setDropoffSequence(item.getDropoffSequence());
            batchItem.setItemStatus(DeliveryBatchItemStatus.OFFERED);
            batchItem.setCreatedAt(now);
            batchItem.setUpdatedAt(now);
            itemRepository.save(batchItem);
            batch.setTotalCodAmount(batch.getTotalCodAmount().add(
                    BatchDecisionPolicy.cod(item.getTotalPrice())));
        }
        batch.setUpdatedAt(now);
        batchRepository.saveAndFlush(batch);
        outboxService.saveEvent(UUID.nameUUIDFromBytes(("batch-offered:" + event.getBatchId())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "DELIVERY_BATCH", event.getBatchId().toString(), "BATCH_SHIPPER_OFFERED",
                KafkaTopicConstants.SHIPPER_OFFERED_TOPIC, event.getBatchId().toString(), event);
        publishOfferPersisted(event, shipperId, expiresAt);
    }

    private void publishOfferPersisted(ShipperFoundEvent event, Long shipperId, LocalDateTime expiresAt) {
        OfferPersistedEvent confirmation = new OfferPersistedEvent();
        confirmation.setSourceCommandEventId(event.getEventId());
        confirmation.setOrderId(event.getOrderId());
        confirmation.setDeliveryId(event.getDeliveryId());
        confirmation.setMatchingSessionId(event.getMatchingSessionId());
        confirmation.setOfferedShipperId(shipperId);
        confirmation.setOfferExpiresAt(expiresAt);
        eventPublisher.publishOfferPersisted(confirmation);
    }
}
