package com.delivery.delivery_service.service;

import com.delivery.delivery.domain.BatchDecisionPolicy;
import static com.delivery.delivery_service.service.DeliveryPolicyAdapter.*;

import com.delivery.delivery_service.common.constants.RoleConstants;
import com.delivery.delivery_service.common.constants.ShipperActionConstants;
import com.delivery.delivery_service.dto.event.ShipperAcceptedEvent;
import com.delivery.delivery_service.dto.request.AcceptBatchRequest;
import com.delivery.delivery_service.dto.response.DeliveryResponse;
import com.delivery.delivery_service.dto.response.DeliveryBatchOfferResponse;
import com.delivery.delivery_service.dto.response.DeliveryOfferResponse;
import com.delivery.delivery_service.entity.Delivery;
import com.delivery.delivery_service.entity.DeliveryBatch;
import com.delivery.delivery_service.entity.DeliveryBatchItem;
import com.delivery.delivery_service.entity.DeliveryBatchItemStatus;
import com.delivery.delivery_service.entity.DeliveryBatchStatus;
import com.delivery.delivery_service.entity.DeliveryStatus;
import com.delivery.delivery_service.exception.AccessDeniedException;
import com.delivery.delivery_service.exception.InvalidStatusException;
import com.delivery.delivery_service.mapper.DeliveryMapper;
import com.delivery.delivery_service.repository.DeliveryBatchItemRepository;
import com.delivery.delivery_service.repository.DeliveryBatchRepository;
import com.delivery.delivery_service.repository.DeliveryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Atomic accept/rollback boundary: a shipper accepts every item or none. */
@Service
public class DeliveryBatchAcceptanceService {
    @Value("${delivery.batch.enabled:false}")
    private boolean batchEnabled;
    private final DeliveryBatchRepository batchRepository;
    private final DeliveryBatchItemRepository itemRepository;
    private final DeliveryRepository deliveryRepository;
    private final DeliveryMapper deliveryMapper;
    private final DeliveryEventPublisher eventPublisher;
    private final OutboxService outboxService;
    private final ShipperIdentityResolver shipperIdentityResolver;

    /** Compatibility constructor for direct unit fixtures. */
    public DeliveryBatchAcceptanceService(DeliveryBatchRepository batchRepository,
                                          DeliveryBatchItemRepository itemRepository,
                                          DeliveryRepository deliveryRepository,
                                          DeliveryMapper deliveryMapper,
                                          DeliveryEventPublisher eventPublisher,
                                          OutboxService outboxService) {
        this(batchRepository, itemRepository, deliveryRepository, deliveryMapper, eventPublisher, outboxService, null);
    }

    @Autowired
    public DeliveryBatchAcceptanceService(DeliveryBatchRepository batchRepository,
                                          DeliveryBatchItemRepository itemRepository,
                                          DeliveryRepository deliveryRepository,
                                          DeliveryMapper deliveryMapper,
                                          DeliveryEventPublisher eventPublisher,
                                          OutboxService outboxService,
                                          ShipperIdentityResolver shipperIdentityResolver) {
        this.batchRepository = batchRepository;
        this.itemRepository = itemRepository;
        this.deliveryRepository = deliveryRepository;
        this.deliveryMapper = deliveryMapper;
        this.eventPublisher = eventPublisher;
        this.outboxService = outboxService;
        this.shipperIdentityResolver = shipperIdentityResolver;
    }

    /** Resolves the actor before entering the canonical batch acceptance path. */
    @Transactional
    public DeliveryResponse accept(AcceptBatchRequest request, Long principalId, Long legacyUserId, String role) {
        if (shipperIdentityResolver == null) {
            throw new AccessDeniedException("Shipper identity resolver is unavailable");
        }
        return accept(request, shipperIdentityResolver.resolveShipperId(principalId, legacyUserId, role), role);
    }

    @Transactional
    public DeliveryResponse accept(AcceptBatchRequest request, Long shipperId, String role) {
        policy(() -> BatchDecisionPolicy.requireEnabled(batchEnabled));
        policy(() -> BatchDecisionPolicy.requireAcceptActor(RoleConstants.SHIPPER.equals(role)));
        policy(() -> BatchDecisionPolicy.requireAcceptRequest(request != null,
                request == null ? null : request.getBatchId(), shipperId));
        DeliveryBatch batch = batchRepository.findByIdForUpdate(request.getBatchId())
                .orElseThrow(() -> new InvalidStatusException("Không tìm thấy batch offer"));
        policy(() -> BatchDecisionPolicy.requireOwner(shipperId, batch.getShipperId()));
        if (decision(() -> BatchDecisionPolicy.onAccept(domain(batch.getStatus()), batch.getOfferExpiresAt(), LocalDateTime.now()))
                == BatchDecisionPolicy.Accept.REPLAY) return firstResponse(request.getBatchId());
        List<DeliveryBatchItem> items = itemRepository.findByBatchIdOrderByPickupSequenceAsc(request.getBatchId());
        DeliveryBatchRouteValidator.validatePersisted(items);
        List<Delivery> deliveries = items.stream().map(item -> deliveryRepository.findByIdForUpdate(item.getDeliveryId())
                .orElseThrow(() -> new InvalidStatusException("Batch delivery không tồn tại"))).toList();
        policy(() -> BatchDecisionPolicy.requireUniqueOrders(deliveries.stream().map(Delivery::getOrderId).toList()));
        for (Delivery delivery : deliveries) {
            policy(() -> BatchDecisionPolicy.requireOffered(domain(delivery.getStatus()), shipperId, delivery.getOfferedShipperId()));
        }
        LocalDateTime now = LocalDateTime.now();
        for (Delivery delivery : deliveries) {
            delivery.setShipperId(shipperId);
            delivery.setStatus(DeliveryStatus.ASSIGNED);
            delivery.setAssignedAt(now);
            delivery.setOfferExpiresAt(null);
            delivery.setUpdatedAt(now);
            if (BatchDecisionPolicy.updatePosition(request.getCurrentLat(), request.getCurrentLng())) {
                delivery.setShipperCurrentLat(request.getCurrentLat());
                delivery.setShipperCurrentLng(request.getCurrentLng());
            }
            deliveryRepository.save(delivery);
            eventPublisher.publishShipperStatusChange(shipperId, "BUSY", delivery.getId(), delivery.getOrderId(),
                    batch.getBatchId(), delivery.getSimulationContext());
            ShipperAcceptedEvent accepted = ShipperAcceptedEvent.builder()
                    .orderId(delivery.getOrderId()).deliveryId(delivery.getId()).shipperId(shipperId)
                    .notes(request.getNotes()).build();
            eventPublisher.publishShipperAcceptedEvent(accepted);
        }
        items.forEach(item -> {
            item.setItemStatus(DeliveryBatchItemStatus.ACCEPTED);
            item.setUpdatedAt(now);
        });
        itemRepository.saveAll(items);
        batch.setStatus(DeliveryBatchStatus.ACCEPTED);
        batch.setAcceptedAt(now);
        batch.setUpdatedAt(now);
        batchRepository.saveAndFlush(batch);
        publishHoldTransition(batch, "COMMITTED", com.delivery.delivery_service.common.constants.KafkaTopicConstants.BATCH_ACCEPTED_TOPIC);
        return deliveryMapper.deliveryToDeliveryResponse(deliveries.get(0));
    }

    @Transactional(readOnly = true)
    public DeliveryBatchOfferResponse currentOffer(Long shipperId, String role) {
        if (!batchEnabled) return null;
        policy(() -> BatchDecisionPolicy.requireViewActor(RoleConstants.SHIPPER.equals(role), shipperId));
        DeliveryBatch batch = batchRepository.findCurrentOffersByShipper(shipperId, LocalDateTime.now(),
                org.springframework.data.domain.PageRequest.of(0, 1)).stream().findFirst().orElse(null);
        if (batch == null) return null;
        List<DeliveryOfferResponse> offers = itemRepository.findByBatchIdOrderByPickupSequenceAsc(batch.getBatchId()).stream()
                .map(item -> {
                    Delivery delivery = deliveryRepository.findById(item.getDeliveryId()).orElse(null);
                    if (delivery == null) return null;
                    DeliveryOfferResponse offer = deliveryMapper.deliveryToOfferResponse(delivery);
                    offer.setBatchId(batch.getBatchId());
                    offer.setPickupSequence(item.getPickupSequence());
                    offer.setDropoffSequence(item.getDropoffSequence());
                    return offer;
                })
                .filter(java.util.Objects::nonNull)
                .toList();
        DeliveryBatchOfferResponse response = new DeliveryBatchOfferResponse();
        response.setBatchId(batch.getBatchId());
        response.setStatus(batch.getStatus());
        response.setRouteVersion(batch.getRouteVersion());
        response.setExpiresAt(batch.getOfferExpiresAt());
        response.setTotalCodAmount(batch.getTotalCodAmount());
        response.setOffers(offers);
        return response;
    }

    @Transactional(readOnly = true)
    public DeliveryBatchOfferResponse currentOffer(Long principalId, Long legacyUserId, String role) {
        if (shipperIdentityResolver == null) {
            throw new AccessDeniedException("Shipper identity resolver is unavailable");
        }
        return currentOffer(shipperIdentityResolver.resolveShipperId(principalId, legacyUserId, role), role);
    }

    private void publishHoldTransition(DeliveryBatch batch, String target, String topic) {
        List<String> holdIds = batch.getCodHoldIds() == null || batch.getCodHoldIds().isBlank()
                ? List.of() : List.of(batch.getCodHoldIds().split(","));
        UUID eventId = UUID.nameUUIDFromBytes(("batch-hold:" + target + ":" + batch.getBatchId())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        outboxService.saveEvent(eventId, "DELIVERY_BATCH", batch.getBatchId().toString(),
                "BATCH_COD_HOLD_" + target, topic, batch.getBatchId().toString(),
                Map.of("batchId", batch.getBatchId().toString(), "holdIds", holdIds, "target", target));
    }

    private DeliveryResponse firstResponse(java.util.UUID batchId) {
        DeliveryBatchItem item = itemRepository.findByBatchIdOrderByPickupSequenceAsc(batchId).stream()
                .findFirst().orElseThrow(() -> new InvalidStatusException("Batch không có item"));
        Delivery delivery = deliveryRepository.findById(item.getDeliveryId())
                .orElseThrow(() -> new InvalidStatusException("Batch delivery không tồn tại"));
        return deliveryMapper.deliveryToDeliveryResponse(delivery);
    }
}
