package com.delivery.delivery_service.service;

import com.delivery.delivery_service.dto.event.ShipperNotFoundEvent;
import com.delivery.delivery_service.entity.Delivery;
import com.delivery.delivery_service.entity.DeliveryStatus;
import com.delivery.delivery_service.repository.DeliveryInboundReceiptRepository;
import com.delivery.delivery_service.repository.DeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class DeliveryInboundReceiptTransactionIntegrationTest {

    @Autowired DeliveryInboundReceiptRepository repository;
    @Autowired DeliveryRepository deliveryRepository;
    @Autowired DeliveryInboundReceiptService receipts;
    @Autowired DeliverySagaCommandProcessor processor;
    @Autowired TransactionTemplate transactionTemplate;

    @BeforeEach
    void clean() {
        repository.deleteAll();
        deliveryRepository.deleteAll();
    }

    @Test
    void claimCommitsWithTheEnclosingDeliveryCommandTransaction() {
        UUID eventId = UUID.randomUUID();

        transactionTemplate.executeWithoutResult(status ->
                receipts.claim(eventId, "MARK_SHIPPER_NOT_FOUND", 101L, 202L,
                        "{\"eventId\":\"" + eventId + "\"}"));

        assertThat(repository.findById(eventId)).isPresent();
    }

    @Test
    void rollbackAfterClaimLeavesTheKafkaCommandReplayable() {
        UUID eventId = UUID.randomUUID();
        String payload = "{\"eventId\":\"" + eventId + "\"}";

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            receipts.claim(eventId, "MARK_SHIPPER_NOT_FOUND", 101L, 202L, payload);
            throw new DeliberateRollback();
        })).isInstanceOf(DeliberateRollback.class);

        assertThat(repository.findById(eventId)).isEmpty();
        assertThat(receipts.claim(eventId, "MARK_SHIPPER_NOT_FOUND", 101L, 202L, payload)).isTrue();
    }

    @Test
    void processorCommitsReceiptWithDeliveryMutationAndRollsBackFailedMutation() {
        Delivery delivery = new Delivery();
        delivery.setCreateEventId(UUID.randomUUID());
        delivery.setOrderId(101L);
        delivery.setCreatorId(7L);
        delivery.setStatus(DeliveryStatus.FINDING_SHIPPER);
        delivery = deliveryRepository.saveAndFlush(delivery);

        UUID successfulEventId = UUID.randomUUID();
        ShipperNotFoundEvent successful = noShipper(successfulEventId, delivery.getId(), delivery.getOrderId());
        String successfulPayload = "{\"eventId\":\"" + successfulEventId + "\"}";
        assertThat(processor.applyShipperNotFound(successful, successfulPayload)).isTrue();
        assertThat(repository.findById(successfulEventId)).isPresent();
        assertThat(deliveryRepository.findById(delivery.getId()).orElseThrow().getStatus())
                .isEqualTo(DeliveryStatus.SHIPPER_NOT_FOUND);
        assertThat(processor.applyShipperNotFound(successful, successfulPayload)).isFalse();

        Delivery incompatible = new Delivery();
        incompatible.setCreateEventId(UUID.randomUUID());
        incompatible.setOrderId(102L);
        incompatible.setCreatorId(7L);
        incompatible.setStatus(DeliveryStatus.PENDING);
        incompatible = deliveryRepository.saveAndFlush(incompatible);
        UUID failingEventId = UUID.randomUUID();
        ShipperNotFoundEvent failing = noShipper(
                failingEventId, incompatible.getId(), incompatible.getOrderId());
        assertThatThrownBy(() -> processor.applyShipperNotFound(failing,
                "{\"eventId\":\"" + failingEventId + "\"}"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(repository.findById(failingEventId)).isEmpty();
        assertThat(deliveryRepository.findById(incompatible.getId()).orElseThrow().getStatus())
                .isEqualTo(DeliveryStatus.PENDING);
    }

    private ShipperNotFoundEvent noShipper(UUID eventId, Long deliveryId, Long orderId) {
        ShipperNotFoundEvent event = new ShipperNotFoundEvent();
        event.setEventId(eventId);
        event.setDeliveryId(deliveryId);
        event.setOrderId(orderId);
        event.setRetryAttempts(10);
        return event;
    }

    private static final class DeliberateRollback extends RuntimeException {
    }
}
