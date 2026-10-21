package com.delivery.delivery_service.service.impl;

import com.delivery.delivery.domain.CancellationPolicy;
import com.delivery.delivery.domain.DeliveryCreationPolicy;
import com.delivery.delivery.domain.DeliveryReadPolicy;
import com.delivery.delivery.domain.OfferDecisionPolicy;
import com.delivery.delivery.domain.OfferDecisionRejected;
import com.delivery.delivery.domain.OfferPersistencePolicy;
import com.delivery.delivery.domain.ShipperProgressPolicy;
import com.delivery.delivery_service.common.constants.KafkaTopicConstants;
import com.delivery.delivery_service.common.constants.RoleConstants;
import com.delivery.delivery_service.dto.event.ShipperAcceptedEvent;
import com.delivery.delivery_service.dto.event.OrderCreatedEvent;
import com.delivery.delivery_service.metrics.BusinessMetrics;
import com.delivery.delivery_service.dto.event.OrderCancelledEvent;
import com.delivery.delivery_service.dto.event.ShipperNotFoundEvent;
import com.delivery.delivery_service.dto.event.ShipperFoundEvent;
import com.delivery.delivery_service.dto.event.DeliveryCompletedEvent;
import com.delivery.delivery_service.dto.event.ExpireShipperOfferCommand;
import com.delivery.delivery_service.dto.event.OfferPersistedEvent;
import com.delivery.delivery_service.dto.event.OfferRetiredEvent;
import com.delivery.delivery_service.common.constants.ShipperActionConstants;
import com.delivery.delivery_service.dto.request.AcceptDeliveryRequest;
import com.delivery.delivery_service.dto.response.DeliveryResponse;
import com.delivery.delivery_service.dto.response.DeliveryOfferResponse;
import com.delivery.delivery_service.entity.Delivery;
import com.delivery.delivery_service.entity.DeliveryStatus;
import com.delivery.delivery_service.exception.AccessDeniedException;
import com.delivery.delivery_service.exception.InvalidStatusException;
import com.delivery.delivery_service.exception.ResourceNotFoundException;
import com.delivery.delivery_service.mapper.DeliveryMapper;
import com.delivery.delivery_service.repository.DeliveryRepository;
import com.delivery.delivery_service.repository.ShipperIdentityProjectionRepository;
import com.delivery.delivery_service.repository.DeliveryOfferSessionTombstoneRepository;
import com.delivery.delivery_service.entity.DeliveryOfferSessionTombstone;
import com.delivery.delivery_service.service.DeliveryService;
import com.delivery.delivery_service.service.DeliveryEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import com.delivery.identity.contracts.SimulationContext;

@Slf4j
@Service
public class DeliveryServiceImpl implements DeliveryService {

    private final BusinessMetrics businessMetrics;

    private final DeliveryRepository deliveryRepository;
    private final DeliveryMapper deliveryMapper;
    private final DeliveryEventPublisher deliveryEventPublisher;
    private final com.delivery.delivery_service.service.OutboxService outboxService;
    private final ShipperIdentityProjectionRepository shipperIdentities;
    private final boolean shipperIdentityProjectionEnforced;
    private final com.delivery.delivery_service.service.ShipperIdentityResolver shipperIdentityResolver;
    private final DeliveryOfferSessionTombstoneRepository offerSessionTombstones;
    private final com.delivery.delivery_service.service.DeliveryBatchProgressService batchProgressService;
    private final com.delivery.delivery_service.service.DeliveryBatchLifecycleService batchLifecycleService;
    private final com.delivery.delivery_service.service.DeliveryProofGate deliveryProofGate;
    private final com.delivery.delivery_service.service.DeliveryExceptionService deliveryExceptionService;

    /** Compatibility constructor for direct fixtures; Spring uses the full production constructor below. */
    public DeliveryServiceImpl(DeliveryRepository deliveryRepository,
            DeliveryMapper deliveryMapper,
            DeliveryEventPublisher deliveryEventPublisher,
            com.delivery.delivery_service.service.OutboxService outboxService,
            BusinessMetrics businessMetrics) {
        this(deliveryRepository, deliveryMapper, deliveryEventPublisher, outboxService, businessMetrics,
                null, false, null, null, null, null, null);
    }

    /** Test seam for the terminal handoff policy without full Spring wiring. */
    public DeliveryServiceImpl(DeliveryRepository deliveryRepository,
            DeliveryMapper deliveryMapper,
            DeliveryEventPublisher deliveryEventPublisher,
            com.delivery.delivery_service.service.OutboxService outboxService,
            BusinessMetrics businessMetrics,
            com.delivery.delivery_service.service.DeliveryProofGate deliveryProofGate) {
        this(deliveryRepository, deliveryMapper, deliveryEventPublisher, outboxService, businessMetrics,
                null, false, null, null, null, deliveryProofGate, null);
    }

    @Autowired
    public DeliveryServiceImpl(DeliveryRepository deliveryRepository,
            DeliveryMapper deliveryMapper,
            DeliveryEventPublisher deliveryEventPublisher,
            com.delivery.delivery_service.service.OutboxService outboxService,
            BusinessMetrics businessMetrics,
            ShipperIdentityProjectionRepository shipperIdentities,
            @Value("${app.shipper.identity-projection.enforced:false}") boolean shipperIdentityProjectionEnforced,
            DeliveryOfferSessionTombstoneRepository offerSessionTombstones,
            com.delivery.delivery_service.service.DeliveryBatchProgressService batchProgressService,
            com.delivery.delivery_service.service.DeliveryBatchLifecycleService batchLifecycleService,
            com.delivery.delivery_service.service.DeliveryProofGate deliveryProofGate,
            com.delivery.delivery_service.service.DeliveryExceptionService deliveryExceptionService) {
        this.deliveryRepository = deliveryRepository;
        this.deliveryMapper = deliveryMapper;
        this.deliveryEventPublisher = deliveryEventPublisher;
        this.outboxService = outboxService;
        this.businessMetrics = businessMetrics;
        this.shipperIdentities = shipperIdentities;
        this.shipperIdentityProjectionEnforced = shipperIdentityProjectionEnforced;
        this.shipperIdentityResolver = com.delivery.delivery_service.service.ShipperIdentityResolver.compatibility(
                shipperIdentities, businessMetrics, shipperIdentityProjectionEnforced);
        this.offerSessionTombstones = offerSessionTombstones;
        this.batchProgressService = batchProgressService;
        this.batchLifecycleService = batchLifecycleService;
        this.deliveryProofGate = deliveryProofGate;
        this.deliveryExceptionService = deliveryExceptionService;
    }

    @Override
    @Transactional
    public DeliveryResponse createDeliveryFromOrderEvent(OrderCreatedEvent event) {
        try {
            DeliveryCreationPolicy.requireEvent(createOf(event));
        } catch (OfferDecisionRejected rejected) {
            // Create identity admission historically fails before the persistence error wrapper.
            throw new IllegalArgumentException(rejected.getMessage());
        }
        try {
            Delivery existing = deliveryRepository.findByOrderId(event.getOrderId()).orElse(null);
            if (existing != null) {
                requireMatchingCreateCommand(existing, event);
                log.info("Create-delivery command already applied for order {}, returning delivery {}",
                        event.getOrderId(), existing.getId());
                return deliveryMapper.deliveryToDeliveryResponse(existing);
            }

            // ✅ Tự động tạo delivery record từ OrderCreatedEvent theo Backend Instructions
            Delivery delivery = new Delivery();
            delivery.setSimulationContext(event.getSimulationContext());
            delivery.setCreateEventId(event.getEventId());

            // Set basic order info
            delivery.setOrderId(event.getOrderId());
            // Note: shipperId sẽ được set sau khi có shipper assignment

            // Set pickup location (restaurant)
            delivery.setPickupAddress(event.getRestaurantAddress());

            // ✅ Set default pickup coordinates (có thể improve sau bằng geocoding)

            // Fallback: TP.HCM center coordinates
            delivery.setPickupLat(event.getPickupLat());
            delivery.setPickupLng(event.getPickupLng());

            // Set delivery location
            delivery.setDeliveryAddress(event.getDeliveryAddress());
            delivery.setDeliveryLat(event.getDeliveryLat());
            delivery.setDeliveryLng(event.getDeliveryLng());

            // Shipping fee is server-owned by Order and validated at the Kafka boundary.
            // Delivery must never invent a financial fallback.
            delivery.setShippingFee(event.getShippingFee());
            DeliveryCreationPolicy.Money money = DeliveryCreationPolicy.money(event.getShippingFee(),
                    event.getGrossShippingFee(), event.getCustomerShippingFee(), event.getDiscountAmount(),
                    event.getItemDiscount(), event.getShopDiscount());
            delivery.setGrossShippingFee(money.grossShippingFee());
            delivery.setCustomerShippingFee(money.customerShippingFee());
            delivery.setSubtotalPrice(event.getSubtotalPrice());
            delivery.setItemDiscount(money.itemDiscount());
            delivery.setShopDiscount(money.shopDiscount());
            delivery.setShippingDiscount(event.getShippingDiscount());
            delivery.setPlatformSubsidy(event.getPlatformSubsidy());
            delivery.setPromotionReservationId(event.getPromotionReservationId());
            delivery.setPromotionBreakdown(serializeBreakdown(event.getAppliedVouchers()));

            // ✅ Set COD info (tổng tiền khách trả + phương thức thanh toán)
            delivery.setTotalPrice(event.getTotalPrice());
            delivery.setPaymentMethod(event.getPaymentMethod());

            // Set notes
            delivery.setNotes(event.getNotes());

            // Set initial status - FINDING_SHIPPER (tự động tìm shipper)
            delivery.setStatus(DeliveryStatus.valueOf(DeliveryCreationPolicy.initialStatus().name()));

            // Set timestamps
            delivery.setCreatedAt(LocalDateTime.now());
            delivery.setUpdatedAt(LocalDateTime.now());
            // Delivery ownership belongs to the customer who placed the order.
            // OrderCreatedEvent.creatorId is the restaurant owner and must never
            // be used for customer authorization or notifications.
            delivery.setCreatorId(event.getUserId());
            delivery.setCustomerPrincipalId(event.getUserPrincipalId());
            delivery.setRestaurantId(event.getRestaurantId());
            delivery.setRestaurantOwnerId(event.getCreatorId());
            delivery.setRestaurantOwnerPrincipalId(event.getCreatorPrincipalId());

            // shipperId sẽ là null cho đến khi được assign
            // delivery.setShipperId(null); // default is null

            // Save delivery
            Delivery savedDelivery = deliveryRepository.save(delivery);

            // ✅ PHÁT LỆNH: Lưu kết quả vào Outbox để OutboxRelay gửi cho Saga
            // Saga sẽ nhận event [delivery.created.result] để tiếp tục luồng FIND_SHIPPER
            java.util.Map<String, Object> result = new java.util.HashMap<>();
            result.put("orderId", savedDelivery.getOrderId());
            result.put("deliveryId", savedDelivery.getId());
            result.put("status", savedDelivery.getStatus().name());
            result.put("pickupAddress", savedDelivery.getPickupAddress());
            result.put("pickupLat", savedDelivery.getPickupLat());
            result.put("pickupLng", savedDelivery.getPickupLng());
            result.put("deliveryAddress", savedDelivery.getDeliveryAddress());
            result.put("deliveryLat", savedDelivery.getDeliveryLat());
            result.put("deliveryLng", savedDelivery.getDeliveryLng());
            result.put("totalPrice", savedDelivery.getTotalPrice());
            result.put("shippingFee", savedDelivery.getShippingFee());
            result.put("paymentMethod", savedDelivery.getPaymentMethod());
            result.put("restaurantId", savedDelivery.getRestaurantId());

            outboxService.saveEvent("DELIVERY", savedDelivery.getId().toString(), "DELIVERY_CREATED_RESULT",
                    "delivery.created.result", savedDelivery.getOrderId().toString(), result);
            log.info("✅ [Delivery] Stored creation result in outbox for orderId={}, deliveryId={}",
                    savedDelivery.getOrderId(), savedDelivery.getId());

            return deliveryMapper.deliveryToDeliveryResponse(savedDelivery);

        } catch (IllegalArgumentException | InvalidStatusException businessFailure) {
            // The Kafka boundary must distinguish a deterministic business
            // refusal (which needs a correlated Saga failure event) from an
            // unavailable database/outbox (which must retry). Do not erase the
            // exception type here.
            throw businessFailure;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create delivery from order event: " + e.getMessage(), e);
        }
    }

    private void requireMatchingCreateCommand(Delivery existing, OrderCreatedEvent event) {
        try {
            DeliveryCreationPolicy.requireReplay(existing.getId(), new DeliveryCreationPolicy.Create(
                    existing.getOrderId(), existing.getCreateEventId(), existing.getCreatorId(),
                    existing.getRestaurantId(), existing.getRestaurantOwnerId(), existing.getPickupAddress(),
                    existing.getPickupLat(), existing.getPickupLng(), existing.getDeliveryAddress(),
                    existing.getDeliveryLat(), existing.getDeliveryLng(), existing.getShippingFee(),
                    existing.getTotalPrice(), existing.getGrossShippingFee(), existing.getPromotionReservationId(),
                    existing.getPaymentMethod()), createOf(event));
        } catch (OfferDecisionRejected rejected) {
            throw offerDecisionFailure(rejected);
        }
    }

    private DeliveryCreationPolicy.Create createOf(OrderCreatedEvent event) {
        return event == null ? null : new DeliveryCreationPolicy.Create(
                event.getOrderId(), event.getEventId(), event.getUserId(), event.getRestaurantId(), event.getCreatorId(),
                event.getRestaurantAddress(), event.getPickupLat(), event.getPickupLng(), event.getDeliveryAddress(),
                event.getDeliveryLat(), event.getDeliveryLng(), event.getShippingFee(), event.getTotalPrice(),
                event.getGrossShippingFee(), event.getPromotionReservationId(), event.getPaymentMethod());
    }

    @Override
    @Transactional
    public DeliveryResponse acceptDelivery(AcceptDeliveryRequest request, Long shipperId, String role) {
        log.info("🚚 Shipper {} attempting to accept order {}", shipperId, request.getOrderId());

        OfferDecisionPolicy.Action action;
        try {
            action = OfferDecisionPolicy.requireRequest(RoleConstants.SHIPPER.equals(role), request.getOrderId(),
                    request.getAction(), request.getRejectReason());
        } catch (OfferDecisionRejected rejected) {
            throw offerDecisionFailure(rejected);
        }

        // ✅ Find delivery by order ID
        Delivery delivery = deliveryRepository.findByOrderIdForUpdate(request.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy thông tin giao hàng cho đơn hàng: " + request.getOrderId()));

        OfferDecisionPolicy.Decision decision;
        try {
            // The row lock above prevents two shippers accepting this order; the
            // active-delivery guard is evaluated only for a live ACCEPT.
            decision = OfferDecisionPolicy.decide(action, shipperId, offerOf(delivery),
                    request.getRejectReason(), LocalDateTime.now(), () -> {
                        List<Delivery> active = deliveryRepository.findActiveDeliveriesByShipper(
                                shipperId, org.springframework.data.domain.PageRequest.of(0, 1));
                        if (active == null || active.isEmpty()) return null;
                        log.warn("⚠️ Shipper {} attempted to accept order {} but already has {} active delivery(ies)",
                                shipperId, request.getOrderId(), active.size());
                        return active.get(0).getId();
                    });
        } catch (OfferDecisionRejected rejected) {
            throw offerDecisionFailure(rejected);
        }
        if (decision == OfferDecisionPolicy.Decision.ACCEPT_REPLAY) {
            log.info("Shipper acceptance already applied for order {}, skipping duplicate", request.getOrderId());
            return deliveryMapper.deliveryToDeliveryResponse(delivery);
        }
        if (decision == OfferDecisionPolicy.Decision.REJECT_REPLAY) {
            log.info("Shipper rejection already applied for order {}, skipping duplicate", request.getOrderId());
            return deliveryMapper.deliveryToDeliveryResponse(delivery);
        }

        // ✅ Process based on action
        if (ShipperActionConstants.ACCEPT.equals(request.getAction())) {
            // ACCEPT logic
            delivery.setShipperId(shipperId);
            delivery.setStatus(DeliveryStatus.ASSIGNED);
            businessMetrics.record("delivery_assigned");
            delivery.setAssignedAt(LocalDateTime.now());
            delivery.setOfferExpiresAt(null);
            delivery.setUpdatedAt(LocalDateTime.now());

            // Update shipper location nếu có
            if (request.getCurrentLat() != null && request.getCurrentLng() != null) {
                delivery.setShipperCurrentLat(request.getCurrentLat());
                delivery.setShipperCurrentLng(request.getCurrentLng());
            }

            // Update estimated pickup time
            // estimatedPickupTime is accepted for client compatibility, but it
            // must not be presented as a delivery ETA. No ETA calculator exists
            // in the MVP contract, so the persisted ETA remains null.

            log.info("✅ Shipper {} ACCEPTED order {}", shipperId, request.getOrderId());

        } else if (ShipperActionConstants.REJECT.equals(request.getAction())) {
            // REJECT logic - không assign shipper, reset lại status để tìm shipper mới
            delivery.setShipperId(null);
            delivery.setStatus(DeliveryStatus.FINDING_SHIPPER);
            delivery.setOfferedShipperId(shipperId);
            delivery.setOfferExpiresAt(null);
            retireSession(delivery.getId(), delivery.getOfferedMatchingSessionId());
            delivery.setOfferedMatchingSessionId(null);
            delivery.setUpdatedAt(LocalDateTime.now());
            delivery.setRejectReason(request.getRejectReason());

            log.info("❌ Shipper {} REJECTED order {} - Reason: {} → Status reset to FINDING_SHIPPER for re-assignment",
                    shipperId, request.getOrderId(), request.getRejectReason());
        }

        // Update notes nếu có
        if (request.getNotes() != null && !request.getNotes().trim().isEmpty()) {
            String existingNotes = delivery.getNotes() != null ? delivery.getNotes() : "";
            delivery.setNotes(existingNotes + " | Shipper notes: " + request.getNotes());
        }

        Delivery savedDelivery;
        try {
            // Flush ACCEPT before publishing any event so the partial unique index
            // can atomically enforce one active delivery per shipper.
            savedDelivery = ShipperActionConstants.ACCEPT.equals(request.getAction())
                    ? deliveryRepository.saveAndFlush(delivery)
                    : deliveryRepository.save(delivery);
        } catch (DataIntegrityViolationException e) {
            throw new InvalidStatusException(
                    "Shipper already has another active delivery");
        }

        // ✅ Publish event based on action
        if (ShipperActionConstants.ACCEPT.equals(request.getAction())) {
            publishShipperStatusChange(
                    shipperId, "BUSY", savedDelivery.getId(), savedDelivery.getOrderId(),
                    null, savedDelivery.getSimulationContext());
            publishMatchAcceptedEvent(savedDelivery, shipperId, request);
            log.info("✅ Delivery {} ACCEPTED successfully by shipper {}", delivery.getId(), shipperId);
        } else if (ShipperActionConstants.REJECT.equals(request.getAction())) {
            publishMatchRejectedEvent(savedDelivery, shipperId, request);
            publishShipperStatusChange(
                    shipperId, "AVAILABLE", savedDelivery.getId(), savedDelivery.getOrderId(),
                    null, savedDelivery.getSimulationContext());
            log.info("❌ Delivery {} REJECTED by shipper {} - Reason: {}",
                    delivery.getId(), shipperId, request.getRejectReason());
        }

        DeliveryResponse response = deliveryMapper.deliveryToDeliveryResponse(savedDelivery);

        return response;
    }

    @Override
    @Transactional
    public DeliveryResponse acceptDelivery(AcceptDeliveryRequest request, Long principalId, Long legacyUserId, String role) {
        return acceptDelivery(request, resolveShipperId(principalId, legacyUserId, role), role);
    }

    @Override
    @Transactional
    public DeliveryResponse acceptDelivery(AcceptDeliveryRequest request, Long principalId, Long legacyUserId,
                                           String role, SimulationContext simulationContext) {
        if (request == null || request.getOrderId() == null) {
            throw new InvalidStatusException("Order ID is required");
        }
        Delivery delivery = deliveryRepository.findByOrderIdForUpdate(request.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy thông tin giao hàng cho đơn hàng: " + request.getOrderId()));
        requireCompatibleSimulationContext(delivery.getSimulationContext(), simulationContext);
        return acceptDelivery(request, principalId, legacyUserId, role);
    }

    private void requireCompatibleSimulationContext(SimulationContext deliveryContext,
                                                    SimulationContext actorContext) {
        SimulationContext expected = SimulationContext.orReal(deliveryContext);
        SimulationContext actual = SimulationContext.orReal(actorContext);
        expected.requireValid();
        actual.requireValid();
        if (expected.isSimulation() != actual.isSimulation()) {
            throw new AccessDeniedException("Actor simulation context does not match delivery execution mode");
        }
        if (expected.isSimulation() && (!expected.runId().equals(actual.runId())
                || !expected.cohortId().equals(actual.cohortId()))) {
            throw new AccessDeniedException("Actor simulation context does not match delivery run or cohort");
        }
    }

    @Override
    @Transactional
    public void cacheShipperOffer(ShipperFoundEvent event) {
        if (event == null || event.getEventId() == null
                || event.getDeliveryId() == null || event.getDeliveryId() <= 0
                || event.getOrderId() == null || event.getOrderId() <= 0
                || event.getAvailableShippers() == null || event.getAvailableShippers().size() != 1
                || event.getAvailableShippers().get(0).getShipperId() == null) {
            throw new InvalidStatusException("Invalid single-shipper offer event");
        }

        Delivery delivery = deliveryRepository.findByOrderIdForUpdate(event.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy delivery cho order: " + event.getOrderId()));

        if (!delivery.getId().equals(event.getDeliveryId())) {
            throw new InvalidStatusException("Delivery ID does not match order ID");
        }
        String matchingSessionId = event.getMatchingSessionId() == null || event.getMatchingSessionId().isBlank()
                ? OfferPersistencePolicy.legacySession(event.getEventId())
                : requireMatchingSession(event.getMatchingSessionId());
        if (isRetiredSession(delivery.getId(), matchingSessionId)) {
            log.info("Ignoring delayed cache command for retired delivery/session {}/{}",
                    delivery.getId(), matchingSessionId);
            return;
        }
        Long offeredShipperId = event.getAvailableShippers().get(0).getShipperId();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt;
        OfferPersistencePolicy.CacheDecision decision;
        try {
            expiresAt = OfferPersistencePolicy.expiresAt(event.getFoundAt(), event.getWaitingTimeoutSeconds(), now);
            decision = OfferPersistencePolicy.onShipperFound(domainStatus(delivery), delivery.getOfferedShipperId(),
                    delivery.getOfferExpiresAt(), offeredShipperId, expiresAt, now);
        } catch (OfferDecisionRejected rejected) {
            throw offerDecisionFailure(rejected);
        }
        if (decision == OfferPersistencePolicy.CacheDecision.REPLAY) {
            publishOfferPersisted(event, delivery, matchingSessionId);
            log.info("Shipper offer already applied for delivery {}, confirming replay", delivery.getId());
            return;
        }

        delivery.setOfferedShipperId(offeredShipperId);
        delivery.setOfferExpiresAt(expiresAt);
        delivery.setOfferedMatchingSessionId(matchingSessionId);
        delivery.setStatus(DeliveryStatus.WAIT_SHIPPER_CONFIRM);
        delivery.setUpdatedAt(now);
        deliveryRepository.saveAndFlush(delivery);

        // Notification consumes only this topic, so the offer is visible in the
        // database before the selected shipper sees it.
        outboxService.saveEvent("DELIVERY", delivery.getId().toString(), "SHIPPER_OFFERED",
                KafkaTopicConstants.SHIPPER_OFFERED_TOPIC, delivery.getOrderId().toString(), event);
        publishOfferPersisted(event, delivery, matchingSessionId);
        log.info("📤 Persisted offer for delivery {} to shipper {} until {}",
                delivery.getId(), offeredShipperId, expiresAt);
    }

    @Override
    @Transactional
    public void expireShipperOffer(ExpireShipperOfferCommand command) {
        if (command == null || command.getEventId() == null
                || !positive(command.getOrderId()) || !positive(command.getDeliveryId())
                || !positive(command.getTimedOutShipperId())
                || command.getExpectedOfferExpiresAt() == null) {
            throw new InvalidStatusException("Invalid expire-shipper-offer command");
        }

        Delivery delivery = deliveryRepository.findByOrderIdForUpdate(command.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy delivery cho order: " + command.getOrderId()));
        if (!command.getDeliveryId().equals(delivery.getId())) {
            throw new InvalidStatusException("Delivery ID does not match order ID");
        }

        String matchingSessionId = command.getMatchingSessionId();
        if (matchingSessionId != null && !matchingSessionId.isBlank()) {
            requireMatchingSession(matchingSessionId);
            retireSession(delivery.getId(), matchingSessionId);
        }

        OfferPersistencePolicy.ExpiryDecision decision;
        try {
            decision = OfferPersistencePolicy.onExpire(domainStatus(delivery), matchingSessionId,
                    delivery.getOfferedMatchingSessionId(), delivery.getOfferedShipperId(),
                    delivery.getOfferExpiresAt(), command.getTimedOutShipperId(),
                    command.getExpectedOfferExpiresAt(), LocalDateTime.now());
        } catch (OfferDecisionRejected rejected) {
            throw offerDecisionFailure(rejected);
        }
        switch (decision) {
            case ALREADY_RETIRED ->
                    log.info("Offer timeout already applied for delivery {}, confirming retirement", delivery.getId());
            case STALE_COMMAND -> log.info("Skipping stale offer-timeout command for delivery {}", delivery.getId());
            case EXPIRE -> {
                delivery.setOfferedShipperId(null);
                delivery.setOfferExpiresAt(null);
                delivery.setOfferedMatchingSessionId(null);
                delivery.setStatus(DeliveryStatus.FINDING_SHIPPER);
                delivery.setUpdatedAt(LocalDateTime.now());
                deliveryRepository.save(delivery);
                log.info("Expired shipper offer for delivery {}, Saga may rematch", delivery.getId());
            }
            default -> {
                // ASSIGNED/TERMINAL report the stronger state; a stale session fenced only itself.
            }
        }
        String outcome = OfferPersistencePolicy.retirementOutcome(decision);
        publishOfferRetired(command, delivery, matchingSessionId, outcome);
    }

    private void publishOfferPersisted(ShipperFoundEvent command, Delivery delivery, String matchingSessionId) {
        OfferPersistedEvent result = new OfferPersistedEvent();
        result.setSourceCommandEventId(command.getEventId());
        result.setOrderId(delivery.getOrderId());
        result.setDeliveryId(delivery.getId());
        result.setMatchingSessionId(matchingSessionId);
        result.setOfferedShipperId(delivery.getOfferedShipperId());
        result.setOfferExpiresAt(delivery.getOfferExpiresAt());
        result.setSimulationContext(delivery.getSimulationContext());
        deliveryEventPublisher.publishOfferPersisted(result);
    }

    private void publishOfferRetired(ExpireShipperOfferCommand command, Delivery delivery,
                                     String matchingSessionId, String outcome) {
        OfferRetiredEvent result = new OfferRetiredEvent();
        result.setSourceCommandEventId(command.getEventId());
        result.setOrderId(delivery.getOrderId());
        result.setDeliveryId(delivery.getId());
        result.setMatchingSessionId(matchingSessionId);
        result.setOutcome(outcome);
        result.setShipperId(DeliveryStatus.ASSIGNED.equals(delivery.getStatus()) ? delivery.getShipperId() : null);
        deliveryEventPublisher.publishOfferRetired(result);
    }

    private String requireMatchingSession(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidStatusException("matchingSessionId is required for shipper offer caching");
        }
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException invalid) {
            throw new InvalidStatusException("matchingSessionId must be a UUID");
        }
    }

    private boolean isRetiredSession(Long deliveryId, String matchingSessionId) {
        return matchingSessionId != null && !matchingSessionId.isBlank()
                && offerSessionTombstones != null
                && offerSessionTombstones.existsByDeliveryIdAndMatchingSessionId(deliveryId, matchingSessionId);
    }

    private void retireSession(Long deliveryId, String matchingSessionId) {
        if (matchingSessionId == null || matchingSessionId.isBlank()
                || offerSessionTombstones == null || isRetiredSession(deliveryId, matchingSessionId)) return;
        offerSessionTombstones.save(new DeliveryOfferSessionTombstone(deliveryId, matchingSessionId));
    }

    @Override
    @Transactional
    public DeliveryResponse cancelAssignedDelivery(Long orderId, Long shipperId, String role, String reason) {
        log.info("🔄 Shipper {} requesting to cancel assigned order {}", shipperId, orderId);

        try {
            CancellationPolicy.requireShipperCancelRequest(RoleConstants.SHIPPER.equals(role), orderId);
        } catch (OfferDecisionRejected rejected) {
            throw offerDecisionFailure(rejected);
        }

        // Serialize against pickup/status transitions and competing cancellation.
        Delivery delivery = deliveryRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy thông tin giao hàng cho đơn hàng: " + orderId));

        String cancelNote = CancellationPolicy.shipperCancelReason(reason);
        CancellationPolicy.ShipperCancel decision;
        try {
            // Pickup moves the goods to the shipper; afterwards another process applies
            // (docs/product/features/delivery-matching.md §11).
            decision = CancellationPolicy.onShipperCancel(domainStatus(delivery), delivery.getBatchId() != null,
                    delivery.getShipperId(), shipperId,
                    OfferDecisionPolicy.isRejectReplay(offerOf(delivery), shipperId, cancelNote));
        } catch (OfferDecisionRejected rejected) {
            throw offerDecisionFailure(rejected);
        }
        if (decision == CancellationPolicy.ShipperCancel.REPLAY) {
            log.info("Shipper cancellation already applied for order {}, skipping duplicate", orderId);
            return deliveryMapper.deliveryToDeliveryResponse(delivery);
        }
        if (decision == CancellationPolicy.ShipperCancel.CANCEL_BATCH) {
            if (batchLifecycleService == null) {
                throw new InvalidStatusException("Batch cancellation support is unavailable");
            }
            Delivery cancelled = batchLifecycleService.cancelAcceptedBatch(
                    delivery.getBatchId(), shipperId, cancelNote);
            return deliveryMapper.deliveryToDeliveryResponse(cancelled);
        }

        // ✅ Reset đơn về tìm shipper mới, giải phóng shipper hiện tại
        delivery.setShipperId(null);
        delivery.setStatus(DeliveryStatus.FINDING_SHIPPER);
        delivery.setOfferedShipperId(shipperId);
        delivery.setOfferExpiresAt(null);
        delivery.setUpdatedAt(LocalDateTime.now());
        delivery.setRejectReason(cancelNote);
        String existingNotes = delivery.getNotes() != null ? delivery.getNotes() : "";
        delivery.setNotes(existingNotes + " | Shipper " + shipperId + " huỷ sau accept: " + cancelNote);

        Delivery savedDelivery = deliveryRepository.save(delivery);

        // ✅ Giải phóng shipper (đánh dấu rảnh)
        if (savedDelivery.getBatchId() == null) {
            publishShipperStatusChange(
                    shipperId, "AVAILABLE", savedDelivery.getId(), savedDelivery.getOrderId(),
                    null, savedDelivery.getSimulationContext());
        } else {
            publishShipperStatusChange(
                    shipperId, "AVAILABLE", savedDelivery.getId(), savedDelivery.getOrderId(), savedDelivery.getBatchId(),
                    savedDelivery.getSimulationContext());
        }

        // ✅ Re-trigger tìm shipper mới qua CÙNG cơ chế rematch của Saga
        //    (Saga sẽ gom rejectedShipperId vào excludedShipperIds + áp giới hạn số lần).
        publishShipperRejectedForRematch(savedDelivery, shipperId, cancelNote);

        log.info("✅ Order {} reset to FINDING_SHIPPER after shipper {} cancelled", orderId, shipperId);

        DeliveryResponse response = deliveryMapper.deliveryToDeliveryResponse(savedDelivery);
        return response;
    }

    @Override
    @Transactional
    public DeliveryResponse cancelAssignedDelivery(Long orderId, Long principalId, Long legacyUserId, String role, String reason) {
        return cancelAssignedDelivery(orderId, resolveShipperId(principalId, legacyUserId, role), role, reason);
    }

    @Override
    @Transactional
    public DeliveryResponse cancelAssignedDelivery(Long orderId, Long principalId, Long legacyUserId, String role,
                                                   String reason, SimulationContext simulationContext) {
        Delivery delivery = deliveryRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy thông tin giao hàng cho đơn hàng: " + orderId));
        requireCompatibleSimulationContext(delivery.getSimulationContext(), simulationContext);
        return cancelAssignedDelivery(orderId, principalId, legacyUserId, role, reason);
    }

    /**
     * ✅ Bắn event lên topic 'delivery.shipper-rejected' để Saga re-trigger tìm shipper
     * mới. Dùng chung cho cả REJECT (trước accept) và CANCEL (sau accept).
     */
    private void publishShipperRejectedForRematch(Delivery delivery, Long shipperId, String reason) {
            java.util.Map<String, Object> rejectedEvent = new java.util.HashMap<>();
            rejectedEvent.put("orderId", delivery.getOrderId());
            rejectedEvent.put("deliveryId", delivery.getId());
            rejectedEvent.put("rejectedShipperId", shipperId);
            rejectedEvent.put("rejectReason", reason);
            rejectedEvent.put("pickupAddress", delivery.getPickupAddress());
            rejectedEvent.put("pickupLat", delivery.getPickupLat());
            rejectedEvent.put("pickupLng", delivery.getPickupLng());
            rejectedEvent.put("deliveryAddress", delivery.getDeliveryAddress());
            rejectedEvent.put("deliveryLat", delivery.getDeliveryLat());
            rejectedEvent.put("deliveryLng", delivery.getDeliveryLng());
            rejectedEvent.put("simulationContext", delivery.getSimulationContext());
            rejectedEvent.put("eventType", "SHIPPER_REJECTED");
            rejectedEvent.put("timestamp", System.currentTimeMillis());

            outboxService.saveEvent("DELIVERY", delivery.getId().toString(), "SHIPPER_REJECTED",
                    KafkaTopicConstants.SHIPPER_REJECTED_TOPIC,
                    delivery.getOrderId().toString(), rejectedEvent);

            log.info("📤 Published SHIPPER_REJECTED (rematch) for delivery {}, shipper {}",
                    delivery.getId(), shipperId);
    }

    private static OfferDecisionPolicy.Offer offerOf(Delivery delivery) {
        return new OfferDecisionPolicy.Offer(
                com.delivery.delivery.domain.DeliveryStatus.valueOf(delivery.getStatus().name()),
                delivery.getShipperId(), delivery.getOfferedShipperId(),
                delivery.getOfferExpiresAt(), delivery.getRejectReason());
    }

    private RuntimeException offerDecisionFailure(OfferDecisionRejected rejected) {
        return rejected.kind() == OfferDecisionRejected.Kind.ACCESS_DENIED
                ? new AccessDeniedException(rejected.getMessage())
                : new InvalidStatusException(rejected.getMessage());
    }

    @Override
    @Transactional
    public DeliveryResponse updateDeliveryStatus(Long deliveryId, DeliveryStatus status, Long userId, String role) {
        Delivery delivery = deliveryRepository.findByIdForUpdate(deliveryId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy thông tin giao hàng với ID: " + deliveryId));

        com.delivery.delivery.domain.DeliveryStatus requested = status == null
                ? null : com.delivery.delivery.domain.DeliveryStatus.valueOf(status.name());
        ShipperProgressPolicy.Progress progress;
        try {
            ShipperProgressPolicy.requireAssignedShipper(RoleConstants.SHIPPER.equals(role), userId,
                    delivery.getShipperId());
            ShipperProgressPolicy.requireShipperTarget(requested);
            progress = ShipperProgressPolicy.decide(domainStatus(delivery), requested,
                    value -> DeliveryStatus.valueOf(value.name()).getDescription());
        } catch (OfferDecisionRejected rejected) {
            throw offerDecisionFailure(rejected);
        }
        // A client may retry after the first transaction committed but before it
        // received the response. Return the persisted state without duplicating
        // status/completion outbox events.
        if (progress == ShipperProgressPolicy.Progress.REPLAY) {
            return deliveryMapper.deliveryToDeliveryResponse(delivery);
        }

        if (ShipperProgressPolicy.requiresProofGate(requested) && deliveryProofGate != null) {
            deliveryProofGate.assertTerminalHandoffAllowed(delivery.getId());
        }

        // Lưu old status để publish event
        String oldStatus = delivery.getStatus().name();

        applyShipperStatusTransition(delivery, status);

        Delivery updatedDelivery = deliveryRepository.save(delivery);
        if (status == DeliveryStatus.DELIVERED && deliveryExceptionService != null) {
            deliveryExceptionService.markResolvedAfterSuccessfulDelivery(updatedDelivery);
        }
        boolean batchCompleted = batchProgressService == null
                || batchProgressService.apply(updatedDelivery, status);
        if (ShipperProgressPolicy.releasesShipper(requested, updatedDelivery.getShipperId() != null, batchCompleted)) {
            if (updatedDelivery.getBatchId() == null) {
                publishShipperStatusChange(
                        updatedDelivery.getShipperId(), "AVAILABLE", updatedDelivery.getId(), updatedDelivery.getOrderId(),
                        null, updatedDelivery.getSimulationContext());
            } else {
                publishShipperStatusChange(
                        updatedDelivery.getShipperId(), "AVAILABLE", updatedDelivery.getId(),
                        updatedDelivery.getOrderId(), updatedDelivery.getBatchId(), updatedDelivery.getSimulationContext());
            }
        }

        // ✅ Publish delivery status update event với orderId
        if (delivery.getCustomerPrincipalId() == null) {
            publishDeliveryStatusUpdated(
                    deliveryId, delivery.getOrderId(), delivery.getCreatorId(), delivery.getShipperId(),
                    status.name(), oldStatus, updatedDelivery.getSimulationContext());
        } else {
            publishDeliveryStatusUpdated(
                    deliveryId, delivery.getOrderId(), delivery.getCreatorId(), delivery.getCustomerPrincipalId(),
                    delivery.getShipperId(), status.name(), oldStatus, updatedDelivery.getSimulationContext());
        }

        DeliveryResponse response = deliveryMapper.deliveryToDeliveryResponse(updatedDelivery);

        return response;
    }

    @Override
    @Transactional
    public DeliveryResponse updateDeliveryStatus(Long deliveryId, DeliveryStatus status, Long principalId, Long legacyUserId, String role) {
        Long actorId = RoleConstants.SHIPPER.equals(role) ? resolveShipperId(principalId, legacyUserId, role) : legacyUserId;
        return updateDeliveryStatus(deliveryId, status, actorId, role);
    }

    @Override
    @Transactional
    public DeliveryResponse updateDeliveryStatus(Long deliveryId, DeliveryStatus status, Long principalId,
                                                 Long legacyUserId, String role,
                                                 SimulationContext simulationContext) {
        Delivery delivery = deliveryRepository.findByIdForUpdate(deliveryId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy thông tin giao hàng với ID: " + deliveryId));
        requireCompatibleSimulationContext(delivery.getSimulationContext(), simulationContext);
        return updateDeliveryStatus(deliveryId, status, principalId, legacyUserId, role);
    }

    @Override
    public DeliveryResponse getDeliveryById(Long deliveryId, Long userId, String role) {
        return getDeliveryById(deliveryId, userId, userId, role);
    }

    @Override
    public DeliveryResponse getDeliveryById(Long deliveryId, Long principalId, Long legacyUserId, String role) {
        Delivery delivery = findDeliveryById(deliveryId);
        validateViewPermission(delivery, principalId, legacyUserId, role);
        return deliveryMapper.deliveryToDeliveryResponse(delivery);
    }

    @Override
    public List<DeliveryResponse> getDeliveriesByShipper(Long shipperId, Long userId, String role) {
        validateShipperListPermission(shipperId, userId, role);

        List<Delivery> deliveries = deliveryRepository.findByShipperIdOrderByCreatedAtDesc(
                shipperId, org.springframework.data.domain.PageRequest.of(0, 100));
        return deliveryMapper.deliveriesToDeliveryResponses(deliveries);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeliveryResponse> getDeliveriesByShipper(Long shipperId, Long principalId, Long legacyUserId, String role) {
        Long actorId = DeliveryReadPolicy.listActor(role, legacyUserId, () -> resolveShipperId(principalId, legacyUserId, role));
        return getDeliveriesByShipper(shipperId, actorId, role);
    }

    @Override
    public DeliveryResponse getDeliveryByOrderId(Long orderId, Long userId, String role) {
        return getDeliveryByOrderId(orderId, userId, userId, role);
    }

    @Override
    public DeliveryResponse getDeliveryByOrderId(Long orderId, Long principalId, Long legacyUserId, String role) {
        Delivery delivery = deliveryRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy thông tin giao hàng cho đơn hàng: " + orderId));

        validateViewPermission(delivery, principalId, legacyUserId, role);
        return deliveryMapper.deliveryToDeliveryResponse(delivery);
    }

    @Override
    public List<DeliveryResponse> getActiveDeliveriesByShipper(Long shipperId, Long userId, String role) {
        validateShipperListPermission(shipperId, userId, role);

        List<Delivery> deliveries = deliveryRepository.findActiveDeliveriesByShipper(
                shipperId, org.springframework.data.domain.PageRequest.of(0, 100));
        return deliveryMapper.deliveriesToDeliveryResponses(deliveries);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeliveryResponse> getActiveDeliveriesByShipper(Long shipperId, Long principalId, Long legacyUserId, String role) {
        Long actorId = DeliveryReadPolicy.listActor(role, legacyUserId, () -> resolveShipperId(principalId, legacyUserId, role));
        return getActiveDeliveriesByShipper(shipperId, actorId, role);
    }

    @Override
    @Transactional(readOnly = true)
    public DeliveryOfferResponse getCurrentOffer(Long shipperId, String role) {
        try {
            DeliveryReadPolicy.requireCurrentOffer(shipperId, role);
        } catch (OfferDecisionRejected rejected) {
            throw offerDecisionFailure(rejected);
        }

        List<Delivery> offers = deliveryRepository.findCurrentOffersByShipper(
                shipperId, LocalDateTime.now(), org.springframework.data.domain.PageRequest.of(0, 2));
        try {
            DeliveryReadPolicy.requireSingleOffer(offers.size());
        } catch (OfferDecisionRejected rejected) {
            throw offerDecisionFailure(rejected);
        }
        return offers.isEmpty() ? null : deliveryMapper.deliveryToOfferResponse(offers.get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public DeliveryOfferResponse getCurrentOffer(Long principalId, Long legacyUserId, String role) {
        return getCurrentOffer(resolveShipperId(principalId, legacyUserId, role), role);
    }

    private Delivery findDeliveryById(Long deliveryId) {
        return deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy thông tin giao hàng với ID: " + deliveryId));
    }

    private Long resolveShipperId(Long principalId, Long legacyUserId, String role) {
        return shipperIdentityResolver.resolveShipperId(principalId, legacyUserId, role);
    }

    private void validateViewPermission(Delivery delivery, Long principalId, Long legacyUserId, String role) {
        try {
            String fallback = DeliveryReadPolicy.requireView(role, principalId, legacyUserId,
                    delivery.getCustomerPrincipalId(), delivery.getCreatorId(),
                    delivery.getRestaurantOwnerPrincipalId(), delivery.getRestaurantOwnerId(), delivery.getShipperId(),
                    () -> resolveShipperId(principalId, legacyUserId, role));
            if (fallback != null) businessMetrics.identityLegacyFallback(fallback);
        } catch (OfferDecisionRejected rejected) {
            throw offerDecisionFailure(rejected);
        }
    }

    private void validateShipperListPermission(Long shipperId, Long userId, String role) {
        try {
            DeliveryReadPolicy.requireShipperList(shipperId, userId, role);
        } catch (OfferDecisionRejected rejected) {
            throw offerDecisionFailure(rejected);
        }
    }

    private void applyShipperStatusTransition(Delivery delivery, DeliveryStatus status) {
        delivery.setStatus(status);

        // Cập nhật timestamp theo status
        switch (status) {
            case PICKED_UP:
                delivery.setPickedUpAt(LocalDateTime.now());
                break;
            case DELIVERED:
                delivery.setDeliveredAt(LocalDateTime.now());
                businessMetrics.record("delivery_completed");

                // ✅ Publish DeliveryCompletedEvent để tự động cộng tiền cho shipper
                publishDeliveryCompletedEvent(delivery);

                break;
            case DELIVERING:
                // Không cần cập nhật timestamp đặc biệt
                break;
            default:
                throw new IllegalStateException("Unsupported shipper status target: " + status);
        }
    }

    /**
     * ✅ Publish MatchAcceptedEvent để thông báo cho Notification Service
     */
    private void publishMatchAcceptedEvent(Delivery delivery, Long shipperId, AcceptDeliveryRequest request) {
        try {
            ShipperAcceptedEvent event = ShipperAcceptedEvent.builder()
                    .orderId(delivery.getOrderId())
                    .deliveryId(delivery.getId())
                    .shipperId(shipperId)
                    .notes(request.getNotes())
                    .simulationContext(delivery.getSimulationContext())
                    .build();

            deliveryEventPublisher.publishShipperAcceptedEvent(event);

            log.info("📤 Published ShipperAcceptedEvent for delivery {}, shipper {}",
                    delivery.getId(), shipperId);

        } catch (Exception e) {
            log.error("💥 Failed to publish MatchAcceptedEvent for delivery {}: {}",
                    delivery.getId(), e.getMessage(), e);
            throw new IllegalStateException("Failed to store MatchAcceptedEvent", e);
        }
    }

    /**
     * ✅ Publish ShipperRejectedEvent khi shipper reject đơn
     * Gửi đến topic riêng 'delivery.shipper-rejected' để Saga re-trigger tìm shipper mới
     */
    private void publishMatchRejectedEvent(Delivery delivery, Long shipperId, AcceptDeliveryRequest request) {
        // Dùng chung cơ chế rematch với cancel-after-accept.
        publishShipperRejectedForRematch(delivery, shipperId, request.getRejectReason());
    }

    /**
     * ✅ Publish DeliveryCompletedEvent để tự động cộng tiền vào shipper balance
     */
    private void publishDeliveryCompletedEvent(Delivery delivery) {
        try {
            if (!positive(delivery.getId()) || !positive(delivery.getOrderId())
                    || !positive(delivery.getRestaurantId()) || !positive(delivery.getShipperId())) {
                throw new IllegalStateException("Completed delivery is missing canonical identity fields");
            }
            if (!"COD".equals(delivery.getPaymentMethod())) {
                throw new IllegalStateException("MVP settlement only accepts COD deliveries");
            }
            if (delivery.getShippingFee() == null
                    || delivery.getShippingFee().compareTo(java.math.BigDecimal.ZERO) <= 0
                    || delivery.getTotalPrice() == null
                    || delivery.getTotalPrice().compareTo(delivery.getShippingFee()) <= 0) {
                throw new IllegalStateException("Completed delivery has invalid canonical totals");
            }

            BigDecimal grossShippingFee = delivery.getGrossShippingFee() == null
                    ? delivery.getShippingFee() : delivery.getGrossShippingFee();
            // ✅ 1. Shipper and shipping commission use gross shipping. A
            // freeship subsidy changes only the customer's payable amount.
            // Phí ship: 85% cho Shipper, 15% cho Platform
            java.math.BigDecimal shipperEarnings = com.delivery.delivery_service.common.constants.PricingConstants
                    .calculateShipperEarnings(grossShippingFee);
            java.math.BigDecimal shippingCommission = com.delivery.delivery_service.common.constants.PricingConstants
                    .calculatePlatformCommission(grossShippingFee);

            // ✅ 2. Restaurant commission excludes only shop-funded discount.
            // Platform discount/freeship is a platform subsidy and must not
            // reduce the restaurant's commission base.
            java.math.BigDecimal foodPrice = delivery.getSubtotalPrice() == null
                    ? delivery.getTotalPrice().subtract(grossShippingFee)
                    : delivery.getSubtotalPrice().subtract(
                            delivery.getShopDiscount() == null ? BigDecimal.ZERO : delivery.getShopDiscount());

            // Hoa hồng từ nhà hàng (ví dụ 20% giá món)
            java.math.BigDecimal restaurantCommission = foodPrice
                    .multiply(com.delivery.delivery_service.common.constants.PricingConstants.RESTAURANT_COMMISSION_RATE);
            // Tiền thực nhận của nhà hàng = Giá món - Hoa hồng
            java.math.BigDecimal restaurantEarnings = foodPrice.subtract(restaurantCommission);

            // ✅ 3. Tổng thu nhập của nền tảng (Platform)
            java.math.BigDecimal totalPlatformEarnings = shippingCommission.add(restaurantCommission);

            log.info(
                    "💰 Settlement calculation for delivery {}: foodPrice={}, shipFee={}, shipperGets={}, restaurantGets={}, platformGets={}",
                    delivery.getId(), foodPrice, delivery.getShippingFee(), shipperEarnings, restaurantEarnings,
                    totalPlatformEarnings);

            DeliveryCompletedEvent event = DeliveryCompletedEvent.builder()
                    .eventId(java.util.UUID.randomUUID())
                    .eventType("DELIVERY_COMPLETED")
                    .simulationContext(delivery.getSimulationContext())
                    .deliveryId(delivery.getId())
                    .orderId(delivery.getOrderId())
                    .shipperId(delivery.getShipperId())
                    .restaurantId(delivery.getRestaurantId())
                    .shippingFee(grossShippingFee)
                    .grossShippingFee(grossShippingFee)
                    .customerShippingFee(delivery.getCustomerShippingFee())
                    .subtotalPrice(delivery.getSubtotalPrice())
                    .shopDiscount(delivery.getShopDiscount())
                    .platformSubsidy(delivery.getPlatformSubsidy())
                    .shippingDiscount(delivery.getShippingDiscount())
                    .totalPrice(delivery.getTotalPrice())
                    .shipperEarnings(shipperEarnings)
                    .restaurantEarnings(restaurantEarnings)
                    .restaurantCommission(restaurantCommission)
                    .shippingCommission(shippingCommission)
                    .totalPlatformEarnings(totalPlatformEarnings)
                    .deliveredAt(delivery.getDeliveredAt())
                    .deliveryAddress(delivery.getDeliveryAddress())
                    .paymentMethod(delivery.getPaymentMethod())
                    .build();

            deliveryEventPublisher.publishDeliveryCompletedEvent(event);

            log.info(
                    "✅ Published DeliveryCompletedEvent for delivery {}, shipper will receive {} (85% of {}), restaurant will receive {}",
                    delivery.getId(), shipperEarnings, delivery.getShippingFee(), restaurantEarnings);

        } catch (Exception e) {
            log.error("💥 Failed to publish DeliveryCompletedEvent for delivery {}: {}",
                    delivery.getId(), e.getMessage(), e);
            throw new IllegalStateException("Failed to store DeliveryCompletedEvent", e);
        }
    }

    private boolean positive(Long value) {
        return value != null && value > 0;
    }

    private String serializeBreakdown(java.util.List<java.util.Map<String, Object>> lines) {
        if (lines == null || lines.isEmpty()) return null;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(lines);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void validateOrderCancelledEvent(OrderCancelledEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("OrderCancelledEvent is required");
        }
        if (!positive(event.getOrderId())) {
            throw new IllegalArgumentException("OrderCancelledEvent orderId must be positive");
        }
    }

    private void validateShipperNotFoundEvent(ShipperNotFoundEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("ShipperNotFoundEvent is required");
        }
        if (!positive(event.getDeliveryId())) {
            throw new IllegalArgumentException("ShipperNotFoundEvent deliveryId must be positive");
        }
        if (!positive(event.getOrderId())) {
            throw new IllegalArgumentException("ShipperNotFoundEvent orderId must be positive");
        }
    }

    private boolean sameOfferDeadline(LocalDateTime first, LocalDateTime second) {
        return OfferPersistencePolicy.sameDeadline(first, second);
    }

    private static com.delivery.delivery.domain.DeliveryStatus domainStatus(Delivery delivery) {
        return com.delivery.delivery.domain.DeliveryStatus.valueOf(delivery.getStatus().name());
    }

    @Override
    @Transactional
    public void cancelDeliveryFromOrderCancelledEvent(OrderCancelledEvent event) {
        validateOrderCancelledEvent(event);
        try {
            log.info("🚫 Processing order cancellation for orderId: {}", event.getOrderId());

            // Tìm delivery record theo orderId
            Delivery delivery = deliveryRepository.findByOrderIdForUpdate(event.getOrderId())
                    .orElse(null);

            if (delivery == null) {
                log.warn("⚠️ No delivery found for cancelled order: {}", event.getOrderId());
                return;
            }

            log.info("📦 Found delivery {} for cancelled order: {}, current status: {}",
                    delivery.getId(), event.getOrderId(), delivery.getStatus());

            CancellationPolicy.OrderCancel cancel;
            try {
                cancel = CancellationPolicy.onOrderCancelled(delivery.getId(), domainStatus(delivery));
            } catch (OfferDecisionRejected rejected) {
                // A correlated Saga command must receive an explicit failure after pickup.
                throw offerDecisionFailure(rejected);
            }
            if (cancel == CancellationPolicy.OrderCancel.ALREADY_CANCELLED) {
                log.info("Cancellation already applied for delivery {}, skipping duplicate", delivery.getId());
                return;
            }

            // Cancel is allowed until pickup so Saga compensation cannot leave an orphan delivery.
            {

                DeliveryStatus previousStatus = delivery.getStatus();

                // Cập nhật trạng thái delivery thành CANCELLED
                delivery.setStatus(DeliveryStatus.CANCELLED);
                delivery.setOfferedShipperId(null);
                delivery.setOfferExpiresAt(null);
                delivery.setRejectReason("Order cancelled by user/admin - orderId: " + event.getOrderId());
                delivery.setUpdatedAt(LocalDateTime.now());

                deliveryRepository.save(delivery);

                // ✅ Publish event đánh dấu shipper rảnh (thay thế REST call)
                if (delivery.getShipperId() != null) {
                    if (delivery.getBatchId() == null) {
                        publishShipperStatusChange(
                                delivery.getShipperId(), "AVAILABLE", delivery.getId(), delivery.getOrderId(),
                                null, delivery.getSimulationContext());
                    } else {
                        publishShipperStatusChange(
                                delivery.getShipperId(), "AVAILABLE", delivery.getId(), delivery.getOrderId(), delivery.getBatchId(),
                                delivery.getSimulationContext());
                    }
                }

                // This is the durable acknowledgement that allows Saga to
                // finish cancellation compensation. It shares the same local
                // transaction as the Delivery state transition, so a crash
                // cannot leave a committed cancellation without an eventual
                // confirmation event.
                if (delivery.getCustomerPrincipalId() == null) {
                    publishDeliveryStatusUpdated(
                            delivery.getId(), delivery.getOrderId(), delivery.getCreatorId(), delivery.getShipperId(),
                            DeliveryStatus.CANCELLED.name(), previousStatus.name(), delivery.getSimulationContext());
                } else {
                    publishDeliveryStatusUpdated(
                            delivery.getId(), delivery.getOrderId(), delivery.getCreatorId(), delivery.getCustomerPrincipalId(),
                            delivery.getShipperId(), DeliveryStatus.CANCELLED.name(), previousStatus.name(), delivery.getSimulationContext());
                }

                log.info("✅ Successfully cancelled delivery {} for order: {}",
                        delivery.getId(), event.getOrderId());

            }

        } catch (IllegalArgumentException | InvalidStatusException businessFailure) {
            throw businessFailure;
        } catch (Exception e) {
            log.error("💥 Error processing order cancellation for orderId: {}",
                    event.getOrderId(), e);
            throw new IllegalStateException("Failed to cancel delivery for order " + event.getOrderId(), e);
        }
    }

    /**
     * ✅ Cập nhật delivery status khi không tìm được shipper
     */
    @Override
    @Transactional
    public void updateDeliveryStatusFromShipperNotFoundEvent(ShipperNotFoundEvent event) {
        validateShipperNotFoundEvent(event);
        try {
            log.info("🔄 Processing ShipperNotFoundEvent for delivery: {}, order: {}",
                    event.getDeliveryId(), event.getOrderId());

            // Tìm delivery theo deliveryId
            Delivery delivery = deliveryRepository.findByIdForUpdate(event.getDeliveryId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Delivery not found with id: " + event.getDeliveryId()));

            // Validate order ID match
            if (!delivery.getOrderId().equals(event.getOrderId())) {
                throw new InvalidStatusException("ShipperNotFound orderId does not match delivery");
            }

            CancellationPolicy.NotFound notFound;
            try {
                notFound = CancellationPolicy.onShipperNotFound(domainStatus(delivery));
            } catch (OfferDecisionRejected rejected) {
                throw offerDecisionFailure(rejected);
            }
            if (notFound == CancellationPolicy.NotFound.ALREADY_APPLIED) {
                log.info("Shipper-not-found already applied for delivery {}, skipping duplicate", delivery.getId());
                return;
            }
            if (notFound == CancellationPolicy.NotFound.IGNORE_STALE) {
                log.info("Ignoring stale shipper-not-found for delivery {} already in {}",
                        delivery.getId(), delivery.getStatus());
                return;
            }

            // Cập nhật status thành SHIPPER_NOT_FOUND
            DeliveryStatus previousStatus = delivery.getStatus();
            delivery.setStatus(DeliveryStatus.SHIPPER_NOT_FOUND);
            delivery.setUpdatedAt(LocalDateTime.now());

            deliveryRepository.save(delivery);

            // Notification consumes the canonical delivery.status-updated topic.
            // Persist this event in the same transaction as the terminal status so
            // the customer is notified without introducing a second notification path.
            if (delivery.getCustomerPrincipalId() == null) {
                publishDeliveryStatusUpdated(
                        delivery.getId(), delivery.getOrderId(), delivery.getCreatorId(), delivery.getShipperId(),
                        DeliveryStatus.SHIPPER_NOT_FOUND.name(), previousStatus.name(), delivery.getSimulationContext());
            } else {
                publishDeliveryStatusUpdated(
                        delivery.getId(), delivery.getOrderId(), delivery.getCreatorId(), delivery.getCustomerPrincipalId(),
                        delivery.getShipperId(), DeliveryStatus.SHIPPER_NOT_FOUND.name(), previousStatus.name(), delivery.getSimulationContext());
            }

            log.info("✅ Updated delivery {} status from {} to SHIPPER_NOT_FOUND after {} retry attempts",
                    delivery.getId(), previousStatus, event.getRetryAttempts());

        } catch (Exception e) {
            log.error("💥 Error updating delivery status from ShipperNotFoundEvent for delivery: {}: {}",
                    event.getDeliveryId(), e.getMessage(), e);
            throw new IllegalStateException("Failed to apply shipper-not-found event", e);
        }
    }

    private void publishShipperStatusChange(Long shipperId, String status, Long deliveryId, Long orderId,
                                            UUID batchId, SimulationContext context) {
        SimulationContext normalized = SimulationContext.orReal(context);
        normalized.requireValid();
        if (normalized.isSimulation()) {
            deliveryEventPublisher.publishShipperStatusChange(shipperId, status, deliveryId, orderId,
                    batchId, normalized);
        } else if (batchId == null) {
            deliveryEventPublisher.publishShipperStatusChange(shipperId, status, deliveryId, orderId);
        } else {
            deliveryEventPublisher.publishShipperStatusChange(shipperId, status, deliveryId, orderId, batchId);
        }
    }

    private void publishDeliveryStatusUpdated(Long deliveryId, Long orderId, Long userId, Long shipperId,
                                              String status, String previousStatus, SimulationContext context) {
        SimulationContext normalized = SimulationContext.orReal(context);
        normalized.requireValid();
        if (normalized.isSimulation()) {
            deliveryEventPublisher.publishDeliveryStatusUpdated(deliveryId, orderId, userId, null, shipperId,
                    status, previousStatus, normalized);
        } else {
            deliveryEventPublisher.publishDeliveryStatusUpdated(deliveryId, orderId, userId, shipperId,
                    status, previousStatus);
        }
    }

    private void publishDeliveryStatusUpdated(Long deliveryId, Long orderId, Long userId, Long userPrincipalId,
                                              Long shipperId, String status, String previousStatus,
                                              SimulationContext context) {
        SimulationContext normalized = SimulationContext.orReal(context);
        normalized.requireValid();
        if (normalized.isSimulation()) {
            deliveryEventPublisher.publishDeliveryStatusUpdated(deliveryId, orderId, userId, userPrincipalId,
                    shipperId, status, previousStatus, normalized);
        } else {
            deliveryEventPublisher.publishDeliveryStatusUpdated(deliveryId, orderId, userId, userPrincipalId,
                    shipperId, status, previousStatus);
        }
    }

}
