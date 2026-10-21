package com.delivery.delivery_service.service;

import com.delivery.delivery_service.DeliveryServiceApplication;
import com.delivery.delivery_service.dto.event.ShipperNotFoundEvent;
import com.delivery.delivery_service.entity.Delivery;
import com.delivery.delivery_service.entity.DeliveryStatus;
import com.delivery.delivery_service.entity.OutboxEvent;
import com.delivery.delivery_service.repository.DeliveryInboundReceiptRepository;
import com.delivery.delivery_service.repository.DeliveryRepository;
import com.delivery.delivery_service.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves two independent Delivery consumers converge through the durable
 * receipt/row lock against PostgreSQL rather than H2's transaction emulation.
 */
@SpringBootTest(classes = DeliveryServiceApplication.class, properties = {
        "spring.kafka.listener.auto-startup=false",
        "app.outbox.relay-enabled=false",
        "spring.cloud.discovery.enabled=false",
        "eureka.client.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class DeliverySagaCommandPostgresConcurrencyTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("delivery_concurrency")
            .withUsername("delivery")
            .withPassword("delivery");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
    }

    @Autowired private DeliverySagaCommandProcessor processor;
    @Autowired private DeliveryRepository deliveryRepository;
    @Autowired private DeliveryInboundReceiptRepository receiptRepository;
    @Autowired private OutboxEventRepository outboxRepository;

    @BeforeEach
    void clean() {
        outboxRepository.deleteAll();
        receiptRepository.deleteAll();
        deliveryRepository.deleteAll();
    }

    @Test
    void concurrentSameSagaCommandCreatesOneReceiptAndOneTerminalOutbox() throws Exception {
        Delivery delivery = new Delivery();
        delivery.setCreateEventId(UUID.randomUUID());
        delivery.setOrderId(940001L);
        delivery.setCreatorId(7L);
        delivery.setStatus(DeliveryStatus.FINDING_SHIPPER);
        delivery = deliveryRepository.saveAndFlush(delivery);

        UUID eventId = UUID.randomUUID();
        ShipperNotFoundEvent event = new ShipperNotFoundEvent();
        event.setEventId(eventId);
        event.setOrderId(delivery.getOrderId());
        event.setDeliveryId(delivery.getId());
        event.setRetryAttempts(10);
        String payload = "{\"eventId\":\"" + eventId + "\",\"orderId\":940001,\"deliveryId\":"
                + delivery.getId() + ",\"retryAttempts\":10}";

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> first = executor.submit(() -> invokeTogether(ready, start,
                    () -> processor.applyShipperNotFound(event, payload)));
            Future<Throwable> second = executor.submit(() -> invokeTogether(ready, start,
                    () -> processor.applyShipperNotFound(event, payload)));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS)).isNull();
            assertThat(second.get(30, TimeUnit.SECONDS)).isNull();
        } finally {
            executor.shutdownNow();
        }

        assertThat(receiptRepository.count()).isEqualTo(1);
        assertThat(deliveryRepository.findById(delivery.getId())).get()
                .extracting(Delivery::getStatus)
                .isEqualTo(DeliveryStatus.SHIPPER_NOT_FOUND);
        assertThat(outboxRepository.findAll())
                .extracting(OutboxEvent::getEventType)
                .containsExactly("DELIVERY_STATUS_UPDATED");
    }

    private Throwable invokeTogether(CountDownLatch ready, CountDownLatch start,
                                     ThrowingOperation operation) {
        try {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent Delivery test did not start");
            }
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }
}
