package com.delivery.delivery_service.service;

import com.delivery.delivery_service.dto.event.ShipperFoundEvent;
import com.delivery.delivery_service.dto.event.ShipperNotFoundEvent;
import com.delivery.delivery_service.dto.event.OrderCreatedEvent;
import com.delivery.delivery_service.dto.event.OrderCancelledEvent;
import com.delivery.delivery_service.dto.event.ExpireShipperOfferCommand;
import com.delivery.delivery_service.dto.event.DeliveryCompletedEvent;
import com.delivery.delivery_service.dto.response.DeliveryResponse;
import com.delivery.delivery_service.dto.response.DeliveryOfferResponse;
import com.delivery.delivery_service.dto.request.AcceptDeliveryRequest;
import com.delivery.delivery_service.entity.Delivery;
import com.delivery.delivery_service.entity.DeliveryStatus;
import com.delivery.delivery_service.exception.AccessDeniedException;
import com.delivery.delivery_service.exception.InvalidStatusException;
import com.delivery.delivery_service.mapper.DeliveryMapper;
import com.delivery.delivery_service.repository.DeliveryRepository;
import com.delivery.delivery_service.service.impl.DeliveryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.delivery.identity.contracts.SimulationContext;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class DeliveryShipperOfferTest {

    @Mock DeliveryRepository repository;
    @Mock DeliveryMapper mapper;
    @Mock DeliveryEventPublisher eventPublisher;
    @Mock OutboxService outboxService;
    @Mock com.delivery.delivery_service.metrics.BusinessMetrics businessMetrics;

    private DeliveryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DeliveryServiceImpl(repository, mapper, eventPublisher, outboxService, businessMetrics);
    }

    @Test
    void creationMapsSnapshotDefaultsAndAddressesWithoutInventingFallbacks() {
        OrderCreatedEvent event = exactCreateEvent();
        event.setDiscountAmount(new BigDecimal("3"));
        event.setUserPrincipalId(400L);
        event.setCreatorPrincipalId(500L);
        when(repository.save(any(Delivery.class))).thenAnswer(invocation -> {
            Delivery delivery = invocation.getArgument(0);
            delivery.setId(1L);
            return delivery;
        });
        service.createDeliveryFromOrderEvent(event);
        ArgumentCaptor<Delivery> snapshot = ArgumentCaptor.forClass(Delivery.class);
        verify(repository).save(snapshot.capture());
        Delivery delivery = snapshot.getValue();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FINDING_SHIPPER);
        assertThat(delivery.getGrossShippingFee()).isEqualTo(event.getShippingFee());
        assertThat(delivery.getCustomerShippingFee()).isEqualTo(event.getShippingFee());
        assertThat(delivery.getItemDiscount()).isEqualTo(event.getDiscountAmount());
        assertThat(delivery.getShopDiscount()).isEqualTo(event.getDiscountAmount());
        assertThat(delivery.getPickupAddress()).isEqualTo(event.getRestaurantAddress());
        assertThat(delivery.getDeliveryAddress()).isEqualTo(event.getDeliveryAddress());
        assertThat(delivery.getPickupLat()).isEqualTo(event.getPickupLat());
        assertThat(delivery.getDeliveryLng()).isEqualTo(event.getDeliveryLng());
        assertThat(delivery.getCustomerPrincipalId()).isEqualTo(400L);
        assertThat(delivery.getRestaurantOwnerPrincipalId()).isEqualTo(500L);
        verify(outboxService).saveEvent(eq("DELIVERY"), eq("1"), eq("DELIVERY_CREATED_RESULT"),
                eq("delivery.created.result"), eq("20"), any());
    }

    @Test
    void readDeniesMissingOwnersAndKeepsNotFoundBeforePermissionCheck() {
        Delivery delivery = new Delivery();
        when(repository.findById(1L)).thenReturn(Optional.of(delivery));
        assertThatThrownBy(() -> service.getDeliveryById(1L, 40L, 40L, "USER"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Bạn không có quyền xem thông tin giao hàng này");
        assertThatThrownBy(() -> service.getDeliveryByOrderId(20L, null, null, "OTHER"))
                .isInstanceOf(com.delivery.delivery_service.exception.ResourceNotFoundException.class)
                .hasMessage("Không tìm thấy thông tin giao hàng cho đơn hàng: 20");
        verifyNoInteractions(mapper, businessMetrics);
    }

    @Test
    void readPrincipalWinsAndLegacyFallbackMetricOnlyFollowsSuccessfulOwnership() {
        Delivery delivery = waitingDelivery();
        delivery.setCustomerPrincipalId(400L);
        when(repository.findById(1L)).thenReturn(Optional.of(delivery));
        service.getDeliveryById(1L, 400L, 999L, "USER");
        assertThatThrownBy(() -> service.getDeliveryById(1L, 401L, 40L, "USER"))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(businessMetrics);
        delivery.setCustomerPrincipalId(null);
        service.getDeliveryById(1L, 400L, 40L, "USER");
        verify(businessMetrics).identityLegacyFallback("customer_read");
    }

    @Test
    void persistsExactlyOneOfferBeforePublishingNotificationEvent() {
        Delivery delivery = waitingDelivery();
        when(repository.findByOrderIdForUpdate(20L)).thenReturn(Optional.of(delivery));
        when(repository.saveAndFlush(delivery)).thenReturn(delivery);

        ShipperFoundEvent event = offerEvent(10L);
        service.cacheShipperOffer(event);

        verify(repository).saveAndFlush(delivery);
        verify(outboxService).saveEvent("DELIVERY", "1", "SHIPPER_OFFERED",
                "delivery.shipper-offered", "20", event);
        org.assertj.core.api.Assertions.assertThat(delivery.getOfferedShipperId()).isEqualTo(10L);
        org.assertj.core.api.Assertions.assertThat(delivery.getOfferExpiresAt()).isAfter(LocalDateTime.now());
        org.assertj.core.api.Assertions.assertThat(delivery.getStatus())
                .isEqualTo(DeliveryStatus.WAIT_SHIPPER_CONFIRM);
    }

    @Test
    void exactOfferReplayIsIdempotentAfterWaitTransition() {
        Delivery delivery = waitingDelivery();
        ShipperFoundEvent event = offerEvent(10L);
        LocalDateTime expiresAt = event.getFoundAt().plusSeconds(event.getWaitingTimeoutSeconds());
        delivery.setOfferedShipperId(10L);
        delivery.setOfferExpiresAt(expiresAt);
        delivery.setStatus(DeliveryStatus.WAIT_SHIPPER_CONFIRM);
        when(repository.findByOrderIdForUpdate(20L)).thenReturn(Optional.of(delivery));

        service.cacheShipperOffer(event);

        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(outboxService);
    }

    @Test
    void exactOfferTimeoutClearsOfferAndReturnsDeliveryToFinding() {
        Delivery delivery = waitingDelivery();
        LocalDateTime expiresAt = LocalDateTime.now().minusSeconds(1);
        delivery.setOfferedShipperId(10L);
        delivery.setOfferExpiresAt(expiresAt);
        delivery.setStatus(DeliveryStatus.WAIT_SHIPPER_CONFIRM);
        when(repository.findByOrderIdForUpdate(20L)).thenReturn(Optional.of(delivery));
        when(repository.save(delivery)).thenReturn(delivery);

        ExpireShipperOfferCommand command = expireCommand(10L, expiresAt.plusNanos(500_000));
        service.expireShipperOffer(command);

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FINDING_SHIPPER);
        assertThat(delivery.getOfferedShipperId()).isNull();
        assertThat(delivery.getOfferExpiresAt()).isNull();
        verify(repository).save(delivery);
    }

    @Test
    void delayedTimeoutCannotClearANewerOfferGeneration() {
        Delivery delivery = waitingDelivery();
        LocalDateTime newerExpiry = LocalDateTime.now().plusMinutes(2);
        delivery.setOfferedShipperId(11L);
        delivery.setOfferExpiresAt(newerExpiry);
        delivery.setStatus(DeliveryStatus.WAIT_SHIPPER_CONFIRM);
        when(repository.findByOrderIdForUpdate(20L)).thenReturn(Optional.of(delivery));

        service.expireShipperOffer(expireCommand(10L, LocalDateTime.now().minusSeconds(1)));

        assertThat(delivery.getOfferedShipperId()).isEqualTo(11L);
        assertThat(delivery.getOfferExpiresAt()).isEqualTo(newerExpiry);
        verify(repository, never()).save(any());
    }

    @Test
    void selectedShipperCanRecoverSingleUnexpiredOffer() {
        Delivery delivery = waitingDelivery();
        delivery.setOfferedShipperId(10L);
        delivery.setOfferExpiresAt(LocalDateTime.now().plusMinutes(2));
        delivery.setStatus(DeliveryStatus.WAIT_SHIPPER_CONFIRM);
        DeliveryOfferResponse response = new DeliveryOfferResponse();
        when(repository.findCurrentOffersByShipper(eq(10L), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(delivery));
        when(mapper.deliveryToOfferResponse(delivery)).thenReturn(response);

        org.assertj.core.api.Assertions.assertThat(service.getCurrentOffer(10L, "SHIPPER"))
                .isSameAs(response);
        verify(mapper).deliveryToOfferResponse(delivery);
    }

    @Test
    void currentOfferIsSelfScopedAndFailsClosedOnInvariantViolation() {
        assertThatThrownBy(() -> service.getCurrentOffer(10L, "ADMIN"))
                .isInstanceOf(AccessDeniedException.class);

        Delivery first = waitingDelivery();
        Delivery second = waitingDelivery();
        second.setId(2L);
        when(repository.findCurrentOffersByShipper(eq(10L), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(first, second));

        assertThatThrownBy(() -> service.getCurrentOffer(10L, "SHIPPER"))
                .hasMessageContaining("multiple active offers");
        verifyNoInteractions(mapper);
    }

    @Test
    void currentOfferReturnsNullWhenNoUnexpiredOfferExists() {
        when(repository.findCurrentOffersByShipper(eq(10L), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of());

        org.assertj.core.api.Assertions.assertThat(service.getCurrentOffer(10L, "SHIPPER")).isNull();
        verifyNoInteractions(mapper);
    }

    @Test
    void rejectsShipperWhoWasNotOfferedTheOrder() {
        Delivery delivery = waitingDelivery();
        delivery.setOfferedShipperId(10L);
        delivery.setOfferExpiresAt(LocalDateTime.now().plusMinutes(2));
        delivery.setStatus(DeliveryStatus.WAIT_SHIPPER_CONFIRM);
        when(repository.findByOrderIdForUpdate(20L)).thenReturn(Optional.of(delivery));

        AcceptDeliveryRequest request = new AcceptDeliveryRequest();
        request.setOrderId(20L);
        request.setAction("ACCEPT");

        assertThatThrownBy(() -> service.acceptDelivery(request, 11L, "SHIPPER"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("không được offer");
        verify(repository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void realShipperCannotAcceptASimulationDelivery() {
        Delivery delivery = waitingDelivery();
        delivery.setOfferedShipperId(10L);
        delivery.setOfferExpiresAt(LocalDateTime.now().plusMinutes(2));
        delivery.setStatus(DeliveryStatus.WAIT_SHIPPER_CONFIRM);
        delivery.setSimulationContext(new SimulationContext(SimulationContext.ExecutionMode.SIMULATION,
                UUID.randomUUID(), UUID.randomUUID(), 1L));
        when(repository.findByOrderIdForUpdate(20L)).thenReturn(Optional.of(delivery));
        AcceptDeliveryRequest request = new AcceptDeliveryRequest();
        request.setOrderId(20L);
        request.setAction("ACCEPT");

        assertThatThrownBy(() -> service.acceptDelivery(request, 10L, 10L, "SHIPPER",
                SimulationContext.real()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("simulation context");
        verify(repository, never()).save(any());
    }

    @Test
    void realShipperCannotCancelASimulationDelivery() {
        Delivery delivery = waitingDelivery();
        delivery.setShipperId(10L);
        delivery.setStatus(DeliveryStatus.ASSIGNED);
        delivery.setSimulationContext(new SimulationContext(SimulationContext.ExecutionMode.SIMULATION,
                UUID.randomUUID(), UUID.randomUUID(), 1L));
        when(repository.findByOrderIdForUpdate(20L)).thenReturn(Optional.of(delivery));

        assertThatThrownBy(() -> service.cancelAssignedDelivery(20L, 10L, 10L, "SHIPPER", "test",
                SimulationContext.real()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("simulation context");
        verify(repository, never()).save(any());
    }

    @Test
    void realShipperCannotUpdateASimulationDelivery() {
        Delivery delivery = waitingDelivery();
        delivery.setShipperId(10L);
        delivery.setStatus(DeliveryStatus.ASSIGNED);
        delivery.setSimulationContext(new SimulationContext(SimulationContext.ExecutionMode.SIMULATION,
                UUID.randomUUID(), UUID.randomUUID(), 1L));
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(delivery));

        assertThatThrownBy(() -> service.updateDeliveryStatus(1L, DeliveryStatus.PICKED_UP, 10L, 10L,
                "SHIPPER", SimulationContext.real()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("simulation context");
        verify(repository, never()).save(any());
    }

    @Test
    void userCannotReadAnotherUsersDelivery() {
        Delivery delivery = waitingDelivery();
        delivery.setCreatorId(7L);
        when(repository.findById(1L)).thenReturn(Optional.of(delivery));

        assertThatThrownBy(() -> service.getDeliveryById(1L, 8L, "USER"))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void restaurantOwnerCanReadDeliveryForOwnedRestaurantOrder() {
        Delivery delivery = waitingDelivery();
        delivery.setRestaurantOwnerId(50L);
        DeliveryResponse response = new DeliveryResponse();
        when(repository.findById(1L)).thenReturn(Optional.of(delivery));
        when(mapper.deliveryToDeliveryResponse(delivery)).thenReturn(response);

        org.assertj.core.api.Assertions.assertThat(
                service.getDeliveryById(1L, 50L, "SHOP_OWNER")).isSameAs(response);
    }

    @Test
    void restaurantOwnerCannotReadDeliveryForAnotherRestaurantOwner() {
        Delivery delivery = waitingDelivery();
        delivery.setRestaurantOwnerId(50L);
        when(repository.findById(1L)).thenReturn(Optional.of(delivery));

        assertThatThrownBy(() -> service.getDeliveryById(1L, 51L, "SHOP_OWNER"))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void cancelAfterPickupFailsWithoutPublishingStopMatching() {
        Delivery delivery = waitingDelivery();
        delivery.setStatus(DeliveryStatus.PICKED_UP);
        delivery.setShipperId(10L);
        when(repository.findByOrderIdForUpdate(20L)).thenReturn(Optional.of(delivery));
        OrderCancelledEvent event = new OrderCancelledEvent();
        event.setOrderId(20L);

        assertThatThrownBy(() -> service.cancelDeliveryFromOrderCancelledEvent(event))
                .isInstanceOf(InvalidStatusException.class)
                .hasMessageContaining("Cannot cancel delivery");

        verify(repository, never()).save(any());
        verifyNoInteractions(eventPublisher, outboxService);
    }

    @Test
    void cancellationBeforeAssignmentReliesOnSagaStopMatchingWithoutOrphanTopic() {
        Delivery delivery = waitingDelivery();
        delivery.setStatus(DeliveryStatus.FINDING_SHIPPER);
        when(repository.findByOrderIdForUpdate(20L)).thenReturn(Optional.of(delivery));
        when(repository.save(delivery)).thenReturn(delivery);
        OrderCancelledEvent event = new OrderCancelledEvent();
        event.setOrderId(20L);

        service.cancelDeliveryFromOrderCancelledEvent(event);

        org.assertj.core.api.Assertions.assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.CANCELLED);
        verify(repository).save(delivery);
        verify(eventPublisher).publishDeliveryStatusUpdated(
                1L, 20L, 40L, null, "CANCELLED", "FINDING_SHIPPER");
        verifyNoInteractions(outboxService);
    }

    @Test
    void assignedShipperCancellationLocksBeforeResetAndQueuesConvergenceEvents() {
        Delivery delivery = waitingDelivery();
        delivery.setStatus(DeliveryStatus.ASSIGNED);
        delivery.setShipperId(10L);
        when(repository.findByOrderIdForUpdate(20L)).thenReturn(Optional.of(delivery));
        when(repository.save(delivery)).thenReturn(delivery);
        when(mapper.deliveryToDeliveryResponse(delivery)).thenReturn(new DeliveryResponse());

        service.cancelAssignedDelivery(20L, 10L, "SHIPPER", "vehicle issue");

        InOrder order = inOrder(repository, eventPublisher, outboxService);
        order.verify(repository).findByOrderIdForUpdate(20L);
        order.verify(repository).save(delivery);
        order.verify(eventPublisher).publishShipperStatusChange(10L, "AVAILABLE", 1L, 20L);
        order.verify(outboxService).saveEvent(eq("DELIVERY"), eq("1"), eq("SHIPPER_REJECTED"),
                eq("delivery.shipper-rejected"), eq("20"), any());
        org.assertj.core.api.Assertions.assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FINDING_SHIPPER);
        org.assertj.core.api.Assertions.assertThat(delivery.getShipperId()).isNull();
        org.assertj.core.api.Assertions.assertThat(delivery.getOfferedShipperId()).isEqualTo(10L);
        org.assertj.core.api.Assertions.assertThat(delivery.getOfferExpiresAt()).isNull();
    }

    @Test
    void exactRejectReplayBeforeRematchReturnsCurrentStateWithoutDuplicateEvents() {
        Delivery delivery = waitingDelivery();
        delivery.setStatus(DeliveryStatus.FINDING_SHIPPER);
        delivery.setShipperId(null);
        delivery.setOfferedShipperId(10L);
        delivery.setOfferExpiresAt(null);
        delivery.setRejectReason("too far");
        DeliveryResponse response = new DeliveryResponse();
        when(repository.findByOrderIdForUpdate(20L)).thenReturn(Optional.of(delivery));
        when(mapper.deliveryToDeliveryResponse(delivery)).thenReturn(response);

        AcceptDeliveryRequest request = new AcceptDeliveryRequest();
        request.setOrderId(20L);
        request.setAction("REJECT");
        request.setRejectReason("too far");

        assertThat(service.acceptDelivery(request, 10L, "SHIPPER")).isSameAs(response);

        verify(repository, never()).save(any());
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(eventPublisher, outboxService);
    }

    @Test
    void exactCancelAssignmentReplayBeforeRematchReturnsCurrentStateWithoutDuplicateEvents() {
        Delivery delivery = waitingDelivery();
        delivery.setStatus(DeliveryStatus.FINDING_SHIPPER);
        delivery.setShipperId(null);
        delivery.setOfferedShipperId(10L);
        delivery.setOfferExpiresAt(null);
        delivery.setRejectReason("vehicle issue");
        DeliveryResponse response = new DeliveryResponse();
        when(repository.findByOrderIdForUpdate(20L)).thenReturn(Optional.of(delivery));
        when(mapper.deliveryToDeliveryResponse(delivery)).thenReturn(response);

        assertThat(service.cancelAssignedDelivery(20L, 10L, "SHIPPER", "vehicle issue"))
                .isSameAs(response);

        verify(repository, never()).save(any());
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(eventPublisher, outboxService);
    }

    @Test
    void statusTransitionLocksDeliveryRowBeforeValidationAndOutbox() {
        Delivery delivery = waitingDelivery();
        delivery.setStatus(DeliveryStatus.ASSIGNED);
        delivery.setShipperId(10L);
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(delivery));
        when(repository.save(delivery)).thenReturn(delivery);

        service.updateDeliveryStatus(1L, DeliveryStatus.PICKED_UP, 10L, "SHIPPER");

        InOrder order = inOrder(repository, eventPublisher);
        order.verify(repository).findByIdForUpdate(1L);
        order.verify(repository).save(delivery);
        order.verify(eventPublisher).publishDeliveryStatusUpdated(
                eq(1L), eq(20L), any(), eq(10L), eq("PICKED_UP"), eq("ASSIGNED"));
    }

    @Test
    void completionEventDoesNotInventRestaurantOrCustomerNames() {
        Delivery delivery = waitingDelivery();
        delivery.setStatus(DeliveryStatus.DELIVERING);
        delivery.setShipperId(10L);
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(delivery));
        when(repository.save(delivery)).thenReturn(delivery);

        service.updateDeliveryStatus(1L, DeliveryStatus.DELIVERED, 10L, "SHIPPER");

        ArgumentCaptor<DeliveryCompletedEvent> event = ArgumentCaptor.forClass(DeliveryCompletedEvent.class);
        verify(eventPublisher).publishDeliveryCompletedEvent(event.capture());
        assertThat(event.getValue().getTotalPrice()).isEqualByComparingTo(delivery.getTotalPrice());
        assertThat(event.getValue().getRestaurantName()).isNull();
        assertThat(event.getValue().getCustomerName()).isNull();
    }

    @Test
    void adminCannotUseShipperStatusMutation() {
        Delivery delivery = waitingDelivery();
        delivery.setStatus(DeliveryStatus.ASSIGNED);
        delivery.setShipperId(10L);
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(delivery));

        assertThatThrownBy(() -> service.updateDeliveryStatus(
                1L, DeliveryStatus.PICKED_UP, 99L, "ADMIN"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Chỉ shipper được phân công");

        verify(repository, never()).save(any());
        verifyNoInteractions(eventPublisher, outboxService, mapper);
    }

    @Test
    void anotherShipperCannotUpdateAssignedDelivery() {
        Delivery delivery = waitingDelivery();
        delivery.setStatus(DeliveryStatus.ASSIGNED);
        delivery.setShipperId(10L);
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(delivery));

        assertThatThrownBy(() -> service.updateDeliveryStatus(
                1L, DeliveryStatus.PICKED_UP, 11L, "SHIPPER"))
                .isInstanceOf(AccessDeniedException.class);

        verify(repository, never()).save(any());
        verifyNoInteractions(eventPublisher, outboxService, mapper);
    }

    @Test
    void shipperCannotCancelOrSkipCanonicalStatusSequence() {
        Delivery delivery = waitingDelivery();
        delivery.setStatus(DeliveryStatus.ASSIGNED);
        delivery.setShipperId(10L);
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(delivery));

        assertThatThrownBy(() -> service.updateDeliveryStatus(
                1L, DeliveryStatus.CANCELLED, 10L, "SHIPPER"))
                .hasMessageContaining("PICKED_UP, DELIVERING hoặc DELIVERED");
        assertThatThrownBy(() -> service.updateDeliveryStatus(
                1L, DeliveryStatus.DELIVERING, 10L, "SHIPPER"))
                .hasMessageContaining("Không thể chuyển");

        verify(repository, never()).save(any());
        verifyNoInteractions(eventPublisher, outboxService, mapper);
    }

    @Test
    void exactDeliveredRetryReturnsCurrentStateWithoutDuplicateEvents() {
        Delivery delivery = waitingDelivery();
        delivery.setStatus(DeliveryStatus.DELIVERED);
        delivery.setShipperId(10L);
        DeliveryResponse response = new DeliveryResponse();
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(delivery));
        when(mapper.deliveryToDeliveryResponse(delivery)).thenReturn(response);

        assertThat(service.updateDeliveryStatus(
                1L, DeliveryStatus.DELIVERED, 10L, "SHIPPER")).isSameAs(response);

        verify(repository, never()).save(any());
        verifyNoInteractions(eventPublisher, outboxService);
    }

    @Test
    void shipperCompatibilityListsCapRepositoryQueriesAtOneHundred() {
        when(repository.findByShipperIdOrderByCreatedAtDesc(eq(10L), any(Pageable.class)))
                .thenReturn(List.of());
        when(repository.findActiveDeliveriesByShipper(eq(10L), any(Pageable.class)))
                .thenReturn(List.of());
        when(mapper.deliveriesToDeliveryResponses(List.of())).thenReturn(List.of());

        service.getDeliveriesByShipper(10L, 10L, "SHIPPER");
        service.getActiveDeliveriesByShipper(10L, 10L, "SHIPPER");

        ArgumentCaptor<Pageable> historyPage = ArgumentCaptor.forClass(Pageable.class);
        ArgumentCaptor<Pageable> activePage = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByShipperIdOrderByCreatedAtDesc(eq(10L), historyPage.capture());
        verify(repository).findActiveDeliveriesByShipper(eq(10L), activePage.capture());
        org.assertj.core.api.Assertions.assertThat(historyPage.getValue().getPageSize()).isEqualTo(100);
        org.assertj.core.api.Assertions.assertThat(activePage.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    void shipperListsRequireShipperSelfOrAdminEvenWhenNumericIdentityMatches() {
        assertThatThrownBy(() -> service.getDeliveriesByShipper(10L, 10L, "USER"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.getActiveDeliveriesByShipper(10L, 10L, "SHOP_OWNER"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.getDeliveriesByShipper(10L, 11L, "SHIPPER"))
                .isInstanceOf(AccessDeniedException.class);

        verify(repository, never()).findByShipperIdOrderByCreatedAtDesc(any(), any());
        verify(repository, never()).findActiveDeliveriesByShipper(any(), any());
        verifyNoInteractions(mapper);
    }

    @Test
    void adminMayReadShipperHistoryForSupportWithoutOfferAccess() {
        when(repository.findByShipperIdOrderByCreatedAtDesc(eq(10L), any(Pageable.class)))
                .thenReturn(List.of());
        when(mapper.deliveriesToDeliveryResponses(List.of())).thenReturn(List.of());

        assertThat(service.getDeliveriesByShipper(10L, 99L, "ADMIN")).isEmpty();
        assertThatThrownBy(() -> service.getCurrentOffer(10L, "ADMIN"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void databaseOneActiveGuardFailsBeforePublishingAssignmentEvents() {
        Delivery delivery = waitingDelivery();
        delivery.setOfferedShipperId(10L);
        delivery.setOfferExpiresAt(LocalDateTime.now().plusMinutes(2));
        delivery.setStatus(DeliveryStatus.WAIT_SHIPPER_CONFIRM);
        when(repository.findByOrderIdForUpdate(20L)).thenReturn(Optional.of(delivery));
        when(repository.findActiveDeliveriesByShipper(eq(10L), any(Pageable.class)))
                .thenReturn(List.of());
        when(repository.saveAndFlush(delivery))
                .thenThrow(new DataIntegrityViolationException("duplicate active shipper"));

        AcceptDeliveryRequest request = new AcceptDeliveryRequest();
        request.setOrderId(20L);
        request.setAction("ACCEPT");

        assertThatThrownBy(() -> service.acceptDelivery(request, 10L, "SHIPPER"))
                .hasMessageContaining("already has another active delivery");
        verifyNoInteractions(eventPublisher);
        verifyNoInteractions(outboxService);
    }

    @Test
    void publishesCanonicalCodAmountAndPaymentMethodForSagaMatching() {
        OrderCreatedEvent event = new OrderCreatedEvent();
        event.setEventId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        event.setOrderId(20L);
        event.setUserId(40L);
        event.setRestaurantId(30L);
        event.setCreatorId(50L); // restaurant owner, not delivery owner
        event.setTotalPrice(new BigDecimal("120000"));
        event.setShippingFee(new BigDecimal("20000"));
        event.setPaymentMethod("COD");
        event.setPickupLat(10.76);
        event.setPickupLng(106.66);
        event.setDeliveryLat(10.78);
        event.setDeliveryLng(106.68);

        when(repository.save(any(Delivery.class))).thenAnswer(invocation -> {
            Delivery saved = invocation.getArgument(0);
            saved.setId(1L);
            return saved;
        });
        when(mapper.deliveryToDeliveryResponse(any(Delivery.class))).thenReturn(new DeliveryResponse());

        service.createDeliveryFromOrderEvent(event);

        ArgumentCaptor<Delivery> deliveryCaptor = ArgumentCaptor.forClass(Delivery.class);
        verify(repository).save(deliveryCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(deliveryCaptor.getValue().getCreatorId()).isEqualTo(40L);
        org.assertj.core.api.Assertions.assertThat(deliveryCaptor.getValue().getRestaurantOwnerId()).isEqualTo(50L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(outboxService).saveEvent(eq("DELIVERY"), eq("1"), eq("DELIVERY_CREATED_RESULT"),
                eq("delivery.created.result"), eq("20"), payload.capture());
        org.assertj.core.api.Assertions.assertThat(payload.getValue())
                .containsEntry("totalPrice", new BigDecimal("120000"))
                .containsEntry("shippingFee", new BigDecimal("20000"))
                .containsEntry("paymentMethod", "COD")
                .containsEntry("restaurantId", 30L);
    }

    @Test
    void duplicateCreateCommandReturnsExistingDeliveryWithoutAnotherEvent() {
        OrderCreatedEvent event = exactCreateEvent();
        Delivery existing = waitingDelivery();
        DeliveryResponse response = new DeliveryResponse();
        when(repository.findByOrderId(20L)).thenReturn(Optional.of(existing));
        when(mapper.deliveryToDeliveryResponse(existing)).thenReturn(response);

        org.assertj.core.api.Assertions.assertThat(service.createDeliveryFromOrderEvent(event)).isSameAs(response);

        verify(repository, never()).save(any());
        verifyNoInteractions(outboxService);
    }

    @Test
    void createDeliveryRejectsNullOrderEventBeforeRepositoryAccess() {
        assertThatThrownBy(() -> service.createDeliveryFromOrderEvent(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OrderCreatedEvent is required");

        verifyNoInteractions(repository, mapper, eventPublisher, outboxService);
    }

    @Test
    void createReplayMissingCanonicalShippingFeeConflictsWithExistingDelivery() {
        OrderCreatedEvent event = exactCreateEvent();
        event.setShippingFee(null);
        Delivery existing = waitingDelivery();
        when(repository.findByOrderId(20L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.createDeliveryFromOrderEvent(event))
                .isInstanceOf(InvalidStatusException.class)
                .hasMessageContaining("Create-delivery replay conflicts");

        verifyNoInteractions(outboxService);
    }

    @Test
    void differentCreateEventForExistingOrderIsRejected() {
        OrderCreatedEvent event = exactCreateEvent();
        event.setEventId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        Delivery existing = waitingDelivery();
        when(repository.findByOrderId(20L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.createDeliveryFromOrderEvent(event))
                .isInstanceOf(InvalidStatusException.class)
                .hasMessageContaining("replay conflicts");

        verify(repository, never()).save(any());
        verifyNoInteractions(outboxService);
    }

    @Test
    void duplicateAcceptanceBySameShipperIsIdempotent() {
        Delivery delivery = waitingDelivery();
        delivery.setStatus(DeliveryStatus.ASSIGNED);
        delivery.setShipperId(10L);
        DeliveryResponse response = new DeliveryResponse();
        when(repository.findByOrderIdForUpdate(20L)).thenReturn(Optional.of(delivery));
        when(mapper.deliveryToDeliveryResponse(delivery)).thenReturn(response);
        AcceptDeliveryRequest request = new AcceptDeliveryRequest();
        request.setOrderId(20L);
        request.setAction("ACCEPT");

        org.assertj.core.api.Assertions.assertThat(service.acceptDelivery(request, 10L, "SHIPPER"))
                .isSameAs(response);

        verify(repository, never()).save(any());
        verifyNoInteractions(eventPublisher, outboxService);
    }

    @Test
    void acceptanceSetsAssignmentTimestampAtTheAssignmentTransition() {
        Delivery delivery = waitingDelivery();
        delivery.setAssignedAt(null);
        delivery.setOfferedShipperId(10L);
        delivery.setOfferExpiresAt(LocalDateTime.now().plusMinutes(2));
        delivery.setStatus(DeliveryStatus.WAIT_SHIPPER_CONFIRM);
        DeliveryResponse response = new DeliveryResponse();
        when(repository.findByOrderIdForUpdate(20L)).thenReturn(Optional.of(delivery));
        when(repository.findActiveDeliveriesByShipper(eq(10L), any(Pageable.class)))
                .thenReturn(List.of());
        when(repository.saveAndFlush(delivery)).thenReturn(delivery);
        when(mapper.deliveryToDeliveryResponse(delivery)).thenReturn(response);

        AcceptDeliveryRequest request = acceptRequest();
        request.setEstimatedPickupTime(20.0);
        assertThat(service.acceptDelivery(request, 10L, "SHIPPER")).isSameAs(response);

        assertThat(delivery.getAssignedAt()).isNotNull();
        assertThat(delivery.getEstimatedDeliveryTime()).isNull();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.ASSIGNED);
        verify(repository).saveAndFlush(delivery);
    }

    private AcceptDeliveryRequest acceptRequest() {
        AcceptDeliveryRequest request = new AcceptDeliveryRequest();
        request.setOrderId(20L);
        request.setAction("ACCEPT");
        return request;
    }

    @Test
    void shipperNotFoundUsesRowLockBeforeTransition() {
        Delivery delivery = waitingDelivery();
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(delivery));
        ShipperNotFoundEvent event = notFoundEvent();

        service.updateDeliveryStatusFromShipperNotFoundEvent(event);

        InOrder inOrder = inOrder(repository);
        inOrder.verify(repository).findByIdForUpdate(1L);
        inOrder.verify(repository).save(delivery);
        org.assertj.core.api.Assertions.assertThat(delivery.getStatus())
                .isEqualTo(DeliveryStatus.SHIPPER_NOT_FOUND);
        verify(eventPublisher).publishDeliveryStatusUpdated(
                1L, 20L, 40L, null, "SHIPPER_NOT_FOUND", "FINDING_SHIPPER");
    }

    @Test
    void shipperNotFoundRejectsNullCommandBeforeRepositoryAccess() {
        assertThatThrownBy(() -> service.updateDeliveryStatusFromShipperNotFoundEvent(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ShipperNotFoundEvent is required");

        verifyNoInteractions(repository, mapper, eventPublisher, outboxService);
    }

    @Test
    void cancelOrderRejectsNullCommandBeforeRepositoryAccess() {
        assertThatThrownBy(() -> service.cancelDeliveryFromOrderCancelledEvent(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OrderCancelledEvent is required");

        verifyNoInteractions(repository, mapper, eventPublisher, outboxService);
    }

    @Test
    void shipperNotFoundReplayDoesNotPublishDuplicateStatusEvent() {
        Delivery delivery = waitingDelivery();
        delivery.setStatus(DeliveryStatus.SHIPPER_NOT_FOUND);
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(delivery));

        service.updateDeliveryStatusFromShipperNotFoundEvent(notFoundEvent());

        verify(repository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void contradictoryShipperNotFoundStateFailsClosed() {
        Delivery delivery = waitingDelivery();
        delivery.setStatus(DeliveryStatus.WAIT_SHIPPER_CONFIRM);
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(delivery));

        assertThatThrownBy(() -> service.updateDeliveryStatusFromShipperNotFoundEvent(notFoundEvent()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to apply shipper-not-found");
        verify(repository, never()).save(any());
    }

    private Delivery waitingDelivery() {
        Delivery delivery = new Delivery();
        delivery.setId(1L);
        delivery.setCreateEventId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        delivery.setOrderId(20L);
        delivery.setCreatorId(40L);
        delivery.setRestaurantId(30L);
        delivery.setRestaurantOwnerId(50L);
        delivery.setPickupAddress("456 Restaurant Street");
        delivery.setDeliveryAddress("123 Delivery Street");
        delivery.setPickupLat(10.76);
        delivery.setPickupLng(106.66);
        delivery.setDeliveryLat(10.78);
        delivery.setDeliveryLng(106.68);
        delivery.setShippingFee(new BigDecimal("20000"));
        delivery.setTotalPrice(new BigDecimal("120000"));
        delivery.setPaymentMethod("COD");
        delivery.setStatus(DeliveryStatus.FINDING_SHIPPER);
        return delivery;
    }

    private OrderCreatedEvent exactCreateEvent() {
        OrderCreatedEvent event = new OrderCreatedEvent();
        event.setEventId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        event.setOrderId(20L);
        event.setUserId(40L);
        event.setRestaurantId(30L);
        event.setCreatorId(50L);
        event.setRestaurantAddress("456 Restaurant Street");
        event.setDeliveryAddress("123 Delivery Street");
        event.setPickupLat(10.76);
        event.setPickupLng(106.66);
        event.setDeliveryLat(10.78);
        event.setDeliveryLng(106.68);
        event.setShippingFee(new BigDecimal("20000"));
        event.setTotalPrice(new BigDecimal("120000"));
        event.setPaymentMethod("COD");
        return event;
    }

    private ShipperFoundEvent offerEvent(Long shipperId) {
        ShipperFoundEvent event = new ShipperFoundEvent();
        event.setEventId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
        event.setDeliveryId(1L);
        event.setOrderId(20L);
        event.setFoundAt(LocalDateTime.now());
        event.setWaitingTimeoutSeconds(180);
        ShipperFoundEvent.ShipperMatchResult shipper = new ShipperFoundEvent.ShipperMatchResult();
        shipper.setShipperId(shipperId);
        event.setAvailableShippers(List.of(shipper));
        return event;
    }

    private ShipperNotFoundEvent notFoundEvent() {
        ShipperNotFoundEvent event = new ShipperNotFoundEvent();
        event.setDeliveryId(1L);
        event.setOrderId(20L);
        event.setRetryAttempts(3);
        return event;
    }

    private ExpireShipperOfferCommand expireCommand(Long shipperId, LocalDateTime expectedExpiry) {
        ExpireShipperOfferCommand command = new ExpireShipperOfferCommand();
        command.setEventId(UUID.fromString("44444444-4444-4444-4444-444444444444"));
        command.setOrderId(20L);
        command.setDeliveryId(1L);
        command.setTimedOutShipperId(shipperId);
        command.setExpectedOfferExpiresAt(expectedExpiry);
        return command;
    }
}
