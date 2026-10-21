package com.delivery.delivery_service.service;

import com.delivery.delivery_service.entity.OutboxEvent;
import com.delivery.delivery_service.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxMessageRelayTest {

    @Mock OutboxEventRepository repository;
    @Mock KafkaTemplate<String, Object> kafkaTemplate;
    OutboxMessageRelay relay;

    @BeforeEach
    void setUp() {
        relay = new OutboxMessageRelay(repository, kafkaTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(relay, "batchSize", 10);
        ReflectionTestUtils.setField(relay, "sendTimeoutSeconds", 1L);
        ReflectionTestUtils.setField(relay, "maxAttempts", 2);
    }

    @Test
    void successfulSendMarksEventSentAndForwardsStableMetadata() {
        OutboxEvent event = pendingEvent();
        when(repository.lockNextBatch(10)).thenReturn(List.of(event));
        when(kafkaTemplate.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        relay.relayMessages();

        assertThat(event.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.SENT);
        assertThat(event.getSentAt()).isNotNull();
        ArgumentCaptor<org.apache.kafka.clients.producer.ProducerRecord<String, Object>> captor =
                ArgumentCaptor.forClass(org.apache.kafka.clients.producer.ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        assertThat(captor.getValue().topic()).isEqualTo("delivery.created.result");
        assertThat(captor.getValue().key()).isEqualTo("20");
        assertThat(new String(captor.getValue().headers().lastHeader("eventId").value()))
                .isEqualTo(event.getEventId().toString());
        verify(repository).save(event);
    }

    @Test
    void failedSendRetriesAndThenMarksDead() {
        OutboxEvent event = pendingEvent();
        when(repository.lockNextBatch(10)).thenReturn(List.of(event));
        when(kafkaTemplate.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Kafka unavailable")));

        relay.relayMessages();
        assertThat(event.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.PENDING);
        assertThat(event.getAttempts()).isEqualTo(1);

        relay.relayMessages();
        assertThat(event.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.DEAD);
        assertThat(event.getAttempts()).isEqualTo(2);
    }

    @Test
    void leasedSendClaimsOneAtATimeAndAcknowledgesTheSameToken() {
        var leases = org.mockito.Mockito.mock(OutboxLeaseService.class);
        ReflectionTestUtils.setField(relay, "leaseService", leases);
        ReflectionTestUtils.setField(relay, "leaseSeconds", 1L);
        OutboxEvent event = pendingEvent(); event.setId(7L);
        when(leases.claim(org.mockito.ArgumentMatchers.eq(1), any(UUID.class), org.mockito.ArgumentMatchers.eq(11L)))
                .thenReturn(List.of(event), List.of());
        when(kafkaTemplate.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(leases.markSent(org.mockito.ArgumentMatchers.eq(7L), any(UUID.class))).thenReturn(true);
        relay.relayMessages();
        var token = ArgumentCaptor.forClass(UUID.class);
        verify(leases, org.mockito.Mockito.times(2)).claim(org.mockito.ArgumentMatchers.eq(1), token.capture(), org.mockito.ArgumentMatchers.eq(11L));
        verify(leases).markSent(7L, token.getAllValues().get(0));
        assertThat(token.getAllValues().get(1)).isNotEqualTo(token.getAllValues().get(0));
        verify(repository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void leasedFailureIsRecordedAndLaterPollCanRecover() {
        var leases = org.mockito.Mockito.mock(OutboxLeaseService.class);
        ReflectionTestUtils.setField(relay, "leaseService", leases);
        OutboxEvent event = pendingEvent(); event.setId(7L);
        when(leases.claim(org.mockito.ArgumentMatchers.eq(1), any(UUID.class), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of(event), List.of());
        when(kafkaTemplate.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        when(leases.markFailure(org.mockito.ArgumentMatchers.eq(7L),any(UUID.class),any(),org.mockito.ArgumentMatchers.eq(2)))
                .thenThrow(new IllegalStateException("database down"));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(relay::relayMessages);
        verify(leases).markFailure(org.mockito.ArgumentMatchers.eq(7L),any(UUID.class),
                org.mockito.ArgumentMatchers.argThat(e -> e.getCause().getMessage().equals("broker down")),org.mockito.ArgumentMatchers.eq(2));
        verify(leases, org.mockito.Mockito.never()).markSent(org.mockito.ArgumentMatchers.anyLong(),any());
        verify(repository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void claimFailureStopsPollWithoutPublishing() {
        var leases = org.mockito.Mockito.mock(OutboxLeaseService.class);
        ReflectionTestUtils.setField(relay, "leaseService", leases);
        when(leases.claim(org.mockito.ArgumentMatchers.eq(1), any(UUID.class), org.mockito.ArgumentMatchers.anyLong()))
                .thenThrow(new IllegalStateException("database down"));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(relay::relayMessages);
        org.mockito.Mockito.verifyNoInteractions(repository, kafkaTemplate);
    }

    private OutboxEvent pendingEvent() {
        OutboxEvent event = new OutboxEvent();
        event.setEventId(UUID.randomUUID());
        event.setAggregateType("DELIVERY");
        event.setAggregateId("1");
        event.setEventType("DELIVERY_CREATED_RESULT");
        event.setTopic("delivery.created.result");
        event.setEventKey("20");
        event.setPayload("{\"orderId\":20}");
        event.setStatus(OutboxEvent.OutboxStatus.PENDING);
        event.setAttempts(0);
        event.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        event.setCreatedAt(LocalDateTime.now());
        return event;
    }
}
