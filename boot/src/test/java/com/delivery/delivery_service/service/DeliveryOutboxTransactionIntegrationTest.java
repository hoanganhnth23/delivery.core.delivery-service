package com.delivery.delivery_service.service;

import com.delivery.delivery_service.entity.Delivery;
import com.delivery.delivery_service.entity.DeliveryStatus;
import com.delivery.delivery_service.repository.DeliveryRepository;
import com.delivery.delivery_service.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class DeliveryOutboxTransactionIntegrationTest {

    @Autowired DeliveryRepository deliveryRepository;
    @Autowired OutboxEventRepository outboxRepository;
    @Autowired OutboxService outboxService;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void clean() {
        outboxRepository.deleteAll();
        deliveryRepository.deleteAll();
    }

    @Test
    void deliveryAndEventCommitAtomically() throws Exception {
        transactionTemplate.executeWithoutResult(status -> {
            Delivery delivery = deliveryRepository.save(newDelivery(101L));
            outboxService.saveEvent("DELIVERY", delivery.getId().toString(), "DELIVERY_CREATED_RESULT",
                    "delivery.created.result", delivery.getOrderId().toString(),
                    Map.of("deliveryId", delivery.getId(), "orderId", delivery.getOrderId()));
        });

        assertThat(deliveryRepository.count()).isEqualTo(1);
        assertThat(outboxRepository.count()).isEqualTo(1);
        var outbox = outboxRepository.findAll().get(0);
        var payload = objectMapper.readTree(outbox.getPayload());
        assertThat(outbox.getEventType()).isEqualTo("DELIVERY_CREATED_RESULT");
        assertThat(payload.path("eventId").asText()).isEqualTo(outbox.getEventId().toString());
        assertThat(payload.path("deliveryId").asLong()).isPositive();
    }

    @Test
    void rollbackRemovesBothDeliveryAndOutboxEvent() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            Delivery delivery = deliveryRepository.save(newDelivery(102L));
            outboxService.saveEvent("DELIVERY", delivery.getId().toString(), "DELIVERY_CREATED_RESULT",
                    "delivery.created.result", delivery.getOrderId().toString(),
                    Map.of("deliveryId", delivery.getId(), "orderId", delivery.getOrderId()));
            throw new DeliberateRollback();
        })).isInstanceOf(DeliberateRollback.class);

        assertThat(deliveryRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
    }

    private Delivery newDelivery(Long orderId) {
        Delivery delivery = new Delivery();
        delivery.setCreateEventId(UUID.randomUUID());
        delivery.setOrderId(orderId);
        delivery.setCreatorId(10L);
        delivery.setRestaurantId(20L);
        delivery.setStatus(DeliveryStatus.PENDING);
        delivery.setPaymentMethod("COD");
        return delivery;
    }

    private static final class DeliberateRollback extends RuntimeException {
    }
}
