package com.delivery.delivery_service.listener;

import com.delivery.delivery_service.DeliveryServiceApplication;
import com.delivery.delivery_service.entity.Delivery;
import com.delivery.delivery_service.entity.DeliveryStatus;
import com.delivery.delivery_service.entity.OutboxEvent;
import com.delivery.delivery_service.repository.DeliveryInboundReceiptRepository;
import com.delivery.delivery_service.repository.DeliveryRepository;
import com.delivery.delivery_service.repository.OutboxEventRepository;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Rehearses the real Saga-to-Delivery ingress boundary. Two independently
 * booted Delivery application contexts share one consumer group and consume
 * the same event ID from separate Kafka partitions. PostgreSQL must retain one
 * receipt/effect, a fresh group must replay exact records as no-ops, and a
 * contradictory reuse must reach the configured DLT.
 */
@SpringBootTest(classes = DeliveryServiceApplication.class, properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "app.outbox.relay-enabled=false",
        "spring.task.scheduling.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "eureka.client.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class DeliverySagaKafkaPostgresIntegrationTest {

    private static final String CREATE_TOPIC = "saga.command.create-delivery.delivery-inbox-proof";
    private static final String CANCEL_TOPIC = "saga.command.cancel-delivery.delivery-inbox-proof";
    private static final String CACHE_OFFER_TOPIC = "saga.command.cache-shipper-found.delivery-inbox-proof";
    private static final String EXPIRE_OFFER_TOPIC = "saga.command.expire-shipper-offer.delivery-inbox-proof";
    private static final String MARK_NOT_FOUND_TOPIC = "saga.command.mark-shipper-not-found.delivery-inbox-proof";

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("apache/kafka-native:3.8.0"));

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("delivery_saga_kafka")
            .withUsername("delivery")
            .withPassword("delivery");

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        registry.add("app.kafka.topics.mark-shipper-not-found", () -> MARK_NOT_FOUND_TOPIC);
    }

    @Autowired private DeliveryRepository deliveryRepository;
    @Autowired private DeliveryInboundReceiptRepository receiptRepository;
    @Autowired private OutboxEventRepository outboxRepository;
    @Autowired @Qualifier("retryKafkaTemplate") private KafkaTemplate<String, String> rawKafka;

    private final List<ConfigurableApplicationContext> replicas = new ArrayList<>();

    @BeforeEach
    void prepareBoundary() throws Exception {
        closeReplicas();
        outboxRepository.deleteAll();
        receiptRepository.deleteAll();
        deliveryRepository.deleteAll();
    }

    @AfterEach
    void stopReplicas() {
        closeReplicas();
    }

    @Test
    void markShipperNotFoundKafkaPostgresReplayAndContradictoryReuseConvergeAcrossTwoDeliveryReplicas() throws Exception {
        Delivery delivery = new Delivery();
        delivery.setCreateEventId(UUID.randomUUID());
        delivery.setOrderId(970_001L);
        delivery.setCreatorId(7L);
        delivery.setStatus(DeliveryStatus.FINDING_SHIPPER);
        delivery = deliveryRepository.saveAndFlush(delivery);

        UUID eventId = UUID.randomUUID();
        String payload = payload(eventId, delivery.getOrderId(), delivery.getId(), 10);

        long persistedDeliveryId = delivery.getId();
        verifySagaCommandReplay(
                MARK_NOT_FOUND_TOPIC,
                "app.kafka.topics.mark-shipper-not-found",
                Long.toString(delivery.getOrderId()),
                payload,
                payload(eventId, delivery.getOrderId(), delivery.getId(), 11),
                () -> assertOneTerminalEffect(persistedDeliveryId));
    }

    @Test
    void createDeliveryKafkaPostgresReplayAndContradictoryReuseConvergeAcrossTwoDeliveryReplicas() throws Exception {
        long orderId = 970_002L;
        UUID eventId = UUID.randomUUID();
        String payload = createPayload(eventId, orderId, "canonical create");

        verifySagaCommandReplay(
                CREATE_TOPIC,
                "app.kafka.topics.create-delivery",
                Long.toString(orderId),
                payload,
                createPayload(eventId, orderId, "contradictory create"),
                () -> {
                    assertThat(receiptRepository.count()).isEqualTo(1);
                    assertThat(deliveryRepository.findByOrderId(orderId)).get()
                            .extracting(Delivery::getStatus)
                            .isEqualTo(DeliveryStatus.FINDING_SHIPPER);
                    assertThat(outboxRepository.findAll())
                            .extracting(OutboxEvent::getEventType)
                            .containsExactly("DELIVERY_CREATED_RESULT");
                });
    }

    @Test
    void cancelDeliveryKafkaPostgresReplayAndContradictoryReuseConvergeAcrossTwoDeliveryReplicas() throws Exception {
        Delivery delivery = findingDelivery(970_003L);
        UUID eventId = UUID.randomUUID();
        String payload = cancelPayload(eventId, delivery.getOrderId(), "customer cancellation");

        verifySagaCommandReplay(
                CANCEL_TOPIC,
                "app.kafka.topics.cancel-delivery",
                Long.toString(delivery.getOrderId()),
                payload,
                cancelPayload(eventId, delivery.getOrderId(), "contradictory cancellation"),
                () -> {
                    assertThat(receiptRepository.count()).isEqualTo(1);
                    assertThat(deliveryRepository.findById(delivery.getId())).get()
                            .extracting(Delivery::getStatus)
                            .isEqualTo(DeliveryStatus.CANCELLED);
                    assertThat(outboxRepository.findAll())
                            .extracting(OutboxEvent::getEventType)
                            .containsExactly("DELIVERY_STATUS_UPDATED");
                });
    }

    @Test
    void cacheShipperOfferKafkaPostgresReplayAndContradictoryReuseConvergeAcrossTwoDeliveryReplicas() throws Exception {
        Delivery delivery = findingDelivery(970_004L);
        UUID eventId = UUID.randomUUID();
        String payload = cacheOfferPayload(eventId, delivery.getOrderId(), delivery.getId(), "canonical restaurant");

        verifySagaCommandReplay(
                CACHE_OFFER_TOPIC,
                "app.kafka.topics.cache-shipper-found",
                Long.toString(delivery.getOrderId()),
                payload,
                cacheOfferPayload(eventId, delivery.getOrderId(), delivery.getId(), "contradictory restaurant"),
                () -> {
                    assertThat(receiptRepository.count()).isEqualTo(1);
                    assertThat(deliveryRepository.findById(delivery.getId())).get()
                            .satisfies(value -> {
                                assertThat(value.getStatus()).isEqualTo(DeliveryStatus.WAIT_SHIPPER_CONFIRM);
                                assertThat(value.getOfferedShipperId()).isEqualTo(71L);
                                assertThat(value.getOfferExpiresAt()).isAfter(LocalDateTime.now());
                            });
                    assertThat(outboxRepository.findAll())
                            .extracting(OutboxEvent::getEventType)
                            .containsExactlyInAnyOrder("SHIPPER_OFFERED", "OFFER_PERSISTED");
                });
    }

    @Test
    void expireShipperOfferKafkaPostgresReplayAndContradictoryReuseConvergeAcrossTwoDeliveryReplicas() throws Exception {
        LocalDateTime expiredAt = LocalDateTime.now().minusSeconds(30).withNano(0);
        Delivery delivery = waitingDelivery(970_005L, 72L, expiredAt);
        UUID eventId = UUID.randomUUID();
        String payload = expireOfferPayload(eventId, delivery.getOrderId(), delivery.getId(), 72L, expiredAt);
        String contradictory = expireOfferPayload(eventId, delivery.getOrderId(), delivery.getId(), 73L, expiredAt);

        verifySagaCommandReplay(
                EXPIRE_OFFER_TOPIC,
                "app.kafka.topics.expire-shipper-offer",
                Long.toString(delivery.getOrderId()),
                payload,
                contradictory,
                () -> {
                    assertThat(receiptRepository.count()).isEqualTo(1);
                    assertThat(deliveryRepository.findById(delivery.getId())).get()
                            .satisfies(value -> {
                                assertThat(value.getStatus()).isEqualTo(DeliveryStatus.FINDING_SHIPPER);
                                assertThat(value.getOfferedShipperId()).isNull();
                                assertThat(value.getOfferExpiresAt()).isNull();
                            });
                    assertThat(outboxRepository.findAll())
                            .extracting(OutboxEvent::getEventType)
                            .containsExactly("OFFER_RETIRED");
                });
    }

    private void assertOneTerminalEffect(long deliveryId) {
        assertThat(receiptRepository.count()).isEqualTo(1);
        assertThat(deliveryRepository.findById(deliveryId)).get()
                .extracting(Delivery::getStatus)
                .isEqualTo(DeliveryStatus.SHIPPER_NOT_FOUND);
        assertThat(outboxRepository.findAll())
                .extracting(OutboxEvent::getEventType)
                .containsExactly("DELIVERY_STATUS_UPDATED");
    }

    private Delivery findingDelivery(long orderId) {
        Delivery delivery = new Delivery();
        delivery.setCreateEventId(UUID.randomUUID());
        delivery.setOrderId(orderId);
        delivery.setCreatorId(7L);
        delivery.setStatus(DeliveryStatus.FINDING_SHIPPER);
        return deliveryRepository.saveAndFlush(delivery);
    }

    private Delivery waitingDelivery(long orderId, long shipperId, LocalDateTime expiresAt) {
        Delivery delivery = findingDelivery(orderId);
        delivery.setStatus(DeliveryStatus.WAIT_SHIPPER_CONFIRM);
        delivery.setOfferedShipperId(shipperId);
        delivery.setOfferExpiresAt(expiresAt);
        return deliveryRepository.saveAndFlush(delivery);
    }

    private void verifySagaCommandReplay(String topic, String property, String key,
                                         String payload, String contradictory,
                                         ThrowingRunnable invariant) throws Exception {
        createRequiredTopics(topic);
        String replicaGroup = "delivery-saga-inbox-replicas-" + UUID.randomUUID();
        String replayGroup = "delivery-saga-inbox-replay-" + UUID.randomUUID();

        startReplica(replicaGroup, property, topic);
        startReplica(replicaGroup, property, topic);
        await("two Delivery replicas to own the two command partitions for " + topic, () ->
                targetPartitionOwners(replicaGroup, topic).equals(Set.of(0, 1)));

        rawKafka.send(topic, 0, key, payload).get(10, TimeUnit.SECONDS);
        rawKafka.send(topic, 1, key, payload).get(10, TimeUnit.SECONDS);
        await("one Delivery command effect before both source offsets for " + topic, () -> {
            try {
                invariant.run();
                return committedOffsetsAtLeast(replicaGroup, topic, 1, 1);
            } catch (AssertionError | Exception ignored) {
                return false;
            }
        });

        rawKafka.send(topic, 0, key, payload).get(10, TimeUnit.SECONDS);
        await("same-group exact replay to commit as a no-op for " + topic, () ->
                committedOffsetsAtLeast(replicaGroup, topic, 2, 1));
        invariant.run();

        closeReplicas();
        startReplica(replayGroup, property, topic);
        await("fresh Delivery consumer group to replay historical offsets as no-ops for " + topic, () ->
                committedOffsetsAtLeast(replayGroup, topic, 2, 1));
        invariant.run();

        try (KafkaConsumer<String, String> dltConsumer = freshConsumer(topic + ".DLT")) {
            rawKafka.send(topic, 1, key, contradictory).get(10, TimeUnit.SECONDS);
            ConsumerRecord<String, String> dlt = awaitRecord(
                    dltConsumer, topic + ".DLT", "contradictory Delivery receipt reuse to reach the DLT for " + topic);
            assertThat(dlt.value()).isEqualTo(contradictory);
            await("contradictory source offset to be recovered after DLT publication for " + topic, () ->
                    committedOffsetsAtLeast(replayGroup, topic, 2, 2));
        }
        invariant.run();
    }

    private ConfigurableApplicationContext startReplica(String groupId, String topicProperty, String topic) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(DeliveryServiceApplication.class)
                .web(WebApplicationType.NONE)
                .properties(replicaProperties(groupId, topicProperty, topic))
                .run();
        listenerRegistry(context).getListenerContainers().stream()
                .filter(container -> listensTo(container, topic))
                .forEach(MessageListenerContainer::start);
        replicas.add(context);
        return context;
    }

    private Map<String, Object> replicaProperties(String groupId, String topicProperty, String topic) {
        Map<String, Object> properties = new java.util.HashMap<>();
        properties.put("spring.main.banner-mode", "off");
        properties.put("spring.kafka.bootstrap-servers", KAFKA.getBootstrapServers());
        properties.put("spring.kafka.consumer.group-id", groupId);
        properties.put("spring.kafka.listener.auto-startup", "false");
        properties.put("spring.kafka.admin.auto-create", "false");
        properties.put("spring.datasource.url", POSTGRES.getJdbcUrl());
        properties.put("spring.datasource.username", POSTGRES.getUsername());
        properties.put("spring.datasource.password", POSTGRES.getPassword());
        properties.put("spring.datasource.driver-class-name", "org.postgresql.Driver");
        properties.put("spring.jpa.hibernate.ddl-auto", "validate");
        properties.put("spring.flyway.enabled", "true");
        properties.put("spring.flyway.baseline-on-migrate", "true");
        properties.put(topicProperty, topic);
        properties.put("app.outbox.relay-enabled", "false");
        properties.put("spring.task.scheduling.enabled", "false");
        properties.put("spring.cloud.discovery.enabled", "false");
        properties.put("eureka.client.enabled", "false");
        return properties;
    }

    private KafkaListenerEndpointRegistry listenerRegistry(ConfigurableApplicationContext context) {
        return context.getBean(KafkaListenerEndpointRegistry.class);
    }

    private boolean listensTo(MessageListenerContainer container, String topic) {
        String[] topics = container.getContainerProperties().getTopics();
        return topics != null && Arrays.asList(topics).contains(topic);
    }

    private Set<Integer> targetPartitionOwners(String groupId, String topic) {
        try (AdminClient admin = adminClient()) {
            ConsumerGroupDescription group = admin.describeConsumerGroups(List.of(groupId)).all()
                    .get(10, TimeUnit.SECONDS).get(groupId);
            return group.members().stream()
                    .flatMap(member -> member.assignment().topicPartitions().stream())
                    .filter(partition -> topic.equals(partition.topic()))
                    .map(TopicPartition::partition)
                    .collect(java.util.stream.Collectors.toSet());
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    private boolean committedOffsetsAtLeast(String groupId, String topic, long partitionZero, long partitionOne) {
        try (AdminClient admin = adminClient()) {
            Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets =
                    admin.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata()
                            .get(10, TimeUnit.SECONDS);
            return offsets.getOrDefault(new TopicPartition(topic, 0),
                    new org.apache.kafka.clients.consumer.OffsetAndMetadata(0)).offset() >= partitionZero
                    && offsets.getOrDefault(new TopicPartition(topic, 1),
                    new org.apache.kafka.clients.consumer.OffsetAndMetadata(0)).offset() >= partitionOne;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void createRequiredTopics(String topic) throws Exception {
        try (AdminClient admin = adminClient()) {
            admin.createTopics(List.of(
                    new NewTopic(topic, 2, (short) 1),
                    new NewTopic(topic + ".DLT", 2, (short) 1))).all().get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException exception) {
            if (!(exception.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)) {
                throw exception;
            }
        }
    }

    private AdminClient adminClient() {
        return AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers()));
    }

    private KafkaConsumer<String, String> freshConsumer(String topic) {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "delivery-saga-inbox-dlt-proof-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    private ConsumerRecord<String, String> awaitRecord(KafkaConsumer<String, String> consumer,
                                                         String topic, String description) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
                if (topic.equals(record.topic())) {
                    return record;
                }
            }
        }
        fail("Timed out waiting for " + description);
        throw new AssertionError("unreachable");
    }

    private void await(String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for " + description, exception);
            }
        }
        fail("Timed out waiting for " + description);
    }

    private String payload(UUID eventId, long orderId, long deliveryId, int retryAttempts) {
        return "{\"eventId\":\"" + eventId + "\",\"orderId\":" + orderId
                + ",\"deliveryId\":" + deliveryId + ",\"retryAttempts\":" + retryAttempts + "}";
    }

    private String createPayload(UUID eventId, long orderId, String notes) {
        return "{\"eventId\":\"" + eventId + "\",\"orderId\":" + orderId
                + ",\"userId\":7,\"restaurantId\":9,\"creatorId\":10,\"status\":\"CONFIRMED\""
                + ",\"subtotalPrice\":100000,\"discountAmount\":0,\"shippingFee\":15000"
                + ",\"totalPrice\":115000,\"paymentMethod\":\"COD\""
                + ",\"deliveryAddress\":\"123 Delivery Street\",\"deliveryLat\":10.75,\"deliveryLng\":106.68"
                + ",\"restaurantAddress\":\"456 Restaurant Road\",\"pickupLat\":10.76,\"pickupLng\":106.67"
                + ",\"notes\":\"" + notes + "\"}";
    }

    private String cancelPayload(UUID eventId, long orderId, String reason) {
        return "{\"eventId\":\"" + eventId + "\",\"orderId\":" + orderId
                + ",\"currentStatus\":\"CANCELLED\",\"cancelReason\":\"" + reason + "\"}";
    }

    private String cacheOfferPayload(UUID eventId, long orderId, long deliveryId, String restaurantName) {
        return "{\"eventId\":\"" + eventId + "\",\"orderId\":" + orderId
                + ",\"deliveryId\":" + deliveryId
                + ",\"availableShippers\":[{\"shipperId\":71,\"shipperName\":\"Courier\"}]"
                + ",\"foundAt\":\"" + LocalDateTime.now().toString() + "\",\"waitingTimeoutSeconds\":180"
                + ",\"restaurantName\":\"" + restaurantName + "\"}";
    }

    private String expireOfferPayload(UUID eventId, long orderId, long deliveryId,
                                      long shipperId, LocalDateTime expectedExpiry) {
        return "{\"eventId\":\"" + eventId + "\",\"orderId\":" + orderId
                + ",\"deliveryId\":" + deliveryId + ",\"timedOutShipperId\":" + shipperId
                + ",\"expectedOfferExpiresAt\":\"" + expectedExpiry + "\"}";
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private void closeReplicas() {
        for (int index = replicas.size() - 1; index >= 0; index--) {
            replicas.get(index).close();
        }
        replicas.clear();
    }
}
