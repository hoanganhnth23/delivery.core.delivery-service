package com.delivery.delivery_service.service;

import com.delivery.delivery_service.entity.OutboxEvent;
import com.delivery.delivery_service.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OutboxService {
    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;
    private Tracer tracer;

    @Autowired
    void setTracer(Tracer tracer) {
        this.tracer = tracer;
    }

    @Transactional
    public UUID saveEvent(String aggregateType, String aggregateId, String eventType,
                          String topic, String key, Object payload) {
        return saveEvent(UUID.randomUUID(), aggregateType, aggregateId, eventType, topic, key, payload);
    }

    @Transactional
    public UUID saveEvent(UUID eventId, String aggregateType, String aggregateId, String eventType,
                          String topic, String key, Object payload) {
        if (eventId == null) throw new IllegalArgumentException("eventId is required");
        LocalDateTime now = LocalDateTime.now();
        JsonNode tree = objectMapper.valueToTree(payload);
        if (!(tree instanceof ObjectNode objectPayload)) {
            throw new IllegalArgumentException("Outbox payload must serialize to a JSON object");
        }
        objectPayload.put("eventId", eventId.toString());
        if (!objectPayload.hasNonNull("eventType")) objectPayload.put("eventType", eventType);

        OutboxEvent existing = repository.findByEventId(eventId).orElse(null);
        if (existing != null) {
            requireExactReplay(existing, aggregateType, aggregateId, eventType, topic, key, objectPayload);
            return eventId;
        }
        if (!objectPayload.hasNonNull("occurredAt")) objectPayload.put("occurredAt", now.toString());

        OutboxEvent event = new OutboxEvent();
        event.setEventId(eventId);
        event.setAggregateType(requireText(aggregateType, "aggregateType"));
        event.setAggregateId(requireText(aggregateId, "aggregateId"));
        event.setEventType(requireText(eventType, "eventType"));
        event.setTopic(requireText(topic, "topic"));
        event.setEventKey(requireText(key, "key"));
        event.setPayload(objectPayload.toString());
        event.setTraceparent(currentTraceparent());
        event.setStatus(OutboxEvent.OutboxStatus.PENDING);
        event.setAttempts(0);
        event.setNextAttemptAt(now);
        event.setCreatedAt(now);
        repository.save(event);
        return eventId;
    }

    private void requireExactReplay(
            OutboxEvent existing,
            String aggregateType,
            String aggregateId,
            String eventType,
            String topic,
            String key,
            ObjectNode incomingPayload) {
        try {
            JsonNode storedTree = objectMapper.readTree(existing.getPayload());
            if (!(storedTree instanceof ObjectNode storedPayload)) {
                throw new IllegalStateException("Stored outbox payload is not a JSON object");
            }
            ObjectNode storedCanonical = storedPayload.deepCopy();
            // Reparse so numerically equal values do not differ only because one
            // side was constructed as a LongNode and persisted JSON reads as IntNode.
            ObjectNode incomingCanonical = (ObjectNode) objectMapper.readTree(
                    objectMapper.writeValueAsBytes(incomingPayload));
            storedCanonical.remove("occurredAt");
            incomingCanonical.remove("occurredAt");
            if (!existing.getAggregateType().equals(aggregateType)
                    || !existing.getAggregateId().equals(aggregateId)
                    || !existing.getEventType().equals(eventType)
                    || !existing.getTopic().equals(topic)
                    || !existing.getEventKey().equals(key)
                    || !storedCanonical.equals(incomingCanonical)) {
                throw new IllegalArgumentException(
                        "outbox eventId replay has contradictory metadata or payload");
            }
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot validate outbox event replay", exception);
        }
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private String currentTraceparent() {
        if (tracer == null) return null;
        Span span = tracer.currentSpan();
        if (span == null || span.context() == null) return null;
        return "00-" + span.context().traceId() + "-" + span.context().spanId()
                + "-" + (Boolean.TRUE.equals(span.context().sampled()) ? "01" : "00");
    }
}
