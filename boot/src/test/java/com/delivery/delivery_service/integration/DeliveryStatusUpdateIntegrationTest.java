package com.delivery.delivery_service.integration;

import com.delivery.delivery_service.service.DeliveryEventPublisher;
import com.delivery.delivery_service.service.OutboxService;
import com.delivery.delivery.contracts.DeliveryStatusUpdatedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ✅ Test Integration cho Delivery Status Update Event với OrderId
 */
@ExtendWith(MockitoExtension.class)
class DeliveryStatusUpdateIntegrationTest {

    @Test
    void statusPathTopicsCanBeIsolatedWithoutChangingCanonicalDefaults() {
        ReflectionTestUtils.setField(deliveryEventPublisher,
                "deliveryStatusUpdatedTopic", "b8.delivery.status");
        ReflectionTestUtils.setField(deliveryEventPublisher,
                "deliveryCompletedTopic", "b8.delivery.completed");
        ReflectionTestUtils.setField(deliveryEventPublisher,
                "shipperStatusChangeTopic", "b8.shipper.status");

        deliveryEventPublisher.publishDeliveryStatusUpdated(1L, 2L, 3L, 4L,
                "PICKED_UP", "ASSIGNED");

        verify(outboxService).saveEvent(eq("DELIVERY"), eq("1"),
                eq("DELIVERY_STATUS_UPDATED"), eq("b8.delivery.status"), eq("1"), any());
    }

    @Mock
    private OutboxService outboxService;

    @InjectMocks
    private DeliveryEventPublisher deliveryEventPublisher;

    @Test
    void testPublishDeliveryStatusUpdatedWithOrderId() {
        // Given
        Long deliveryId = 123L;
        Long orderId = 456L;
        Long userId = 654L;
        Long shipperId = 987L;
        String newStatus = "DELIVERED";
        String oldStatus = "DELIVERING";

        // When
        deliveryEventPublisher.publishDeliveryStatusUpdated(
                deliveryId, orderId, userId, shipperId, newStatus, oldStatus);

        // Then
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);

        verify(outboxService).saveEvent(eq("DELIVERY"), eq(deliveryId.toString()),
                eq("DELIVERY_STATUS_UPDATED"), topicCaptor.capture(), keyCaptor.capture(), eventCaptor.capture());

        // Verify topic
        assertEquals("delivery.status-updated", topicCaptor.getValue());

        // Verify key (deliveryId)
        assertEquals(deliveryId.toString(), keyCaptor.getValue());

        // Verify event
        DeliveryStatusUpdatedEvent event = (DeliveryStatusUpdatedEvent) eventCaptor.getValue();
        
        assertEquals(deliveryId, event.deliveryId());
        assertEquals(orderId, event.orderId()); // ✅ Verify orderId is included
        assertEquals(userId, event.userId());
        assertEquals(shipperId, event.shipperId());
        assertEquals(newStatus, event.status());
        assertEquals(oldStatus, event.previousStatus());
        assertEquals("DELIVERY_STATUS_UPDATED", event.eventType());
        assertNotNull(event.eventTimestamp());
    }

    @Test
    void testEventStructureForOrderServiceCompatibility() {
        // Given
        Long deliveryId = 789L;
        Long orderId = 101112L;
        String newStatus = "ASSIGNED";
        String oldStatus = "PENDING";

        // When
        deliveryEventPublisher.publishDeliveryStatusUpdated(
                deliveryId, orderId, 202L, 303L, newStatus, oldStatus);

        // Then
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(outboxService).saveEvent(anyString(), anyString(), anyString(),
                anyString(), anyString(), eventCaptor.capture());

        DeliveryStatusUpdatedEvent event = (DeliveryStatusUpdatedEvent) eventCaptor.getValue();

        // ✅ Verify all required fields for Order Service
        assertNotNull(event.deliveryId(), "deliveryId is required");
        assertNotNull(event.orderId(), "orderId is required for order status update");
        assertNotNull(event.userId(), "userId is required for customer notification");
        assertNotNull(event.status(), "status is required");
        assertNotNull(event.previousStatus(), "previousStatus is required");
        assertNotNull(event.eventType(), "eventType is required");
        assertNotNull(event.eventTimestamp(), "eventTimestamp is required");

        // ✅ Verify event type
        assertEquals("DELIVERY_STATUS_UPDATED", event.eventType());
    }

    @Test
    void testDeliveryStatusMappingScenarios() {
        // Test các scenario mapping delivery status → order status
        Long deliveryId = 555L;
        Long orderId = 666L;

        // Scenario 1: PENDING → ASSIGNED
        deliveryEventPublisher.publishDeliveryStatusUpdated(deliveryId, orderId, 1L, 2L, "ASSIGNED", "PENDING");
        
        // Scenario 2: ASSIGNED → PICKED_UP
        deliveryEventPublisher.publishDeliveryStatusUpdated(deliveryId, orderId, 1L, 2L, "PICKED_UP", "ASSIGNED");
        
        // Scenario 3: PICKED_UP → DELIVERING
        deliveryEventPublisher.publishDeliveryStatusUpdated(deliveryId, orderId, 1L, 2L, "DELIVERING", "PICKED_UP");
        
        // Scenario 4: DELIVERING → DELIVERED
        deliveryEventPublisher.publishDeliveryStatusUpdated(deliveryId, orderId, 1L, 2L, "DELIVERED", "DELIVERING");

        // Verify all events were sent
        verify(outboxService, times(4)).saveEvent(anyString(), anyString(), anyString(),
                anyString(), anyString(), any());
    }
}

/**
 * ✅ Expected Order Service Mapping:
 * 
 * Delivery Status → Order Status
 * - PENDING → (no change, waiting for assignment)  
 * - ASSIGNED → ASSIGNED_TO_SHIPPER
 * - PICKED_UP → IN_DELIVERY
 * - DELIVERING → IN_DELIVERY  
 * - DELIVERED → DELIVERED
 * - CANCELLED → CANCELLED
 */
