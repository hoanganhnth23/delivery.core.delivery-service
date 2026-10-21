package com.delivery.delivery_service.service;

import com.delivery.delivery_service.entity.ShipperIdentityInboxReceipt;
import com.delivery.delivery_service.entity.ShipperIdentityProjection;
import com.delivery.delivery_service.repository.ShipperIdentityInboxReceiptRepository;
import com.delivery.delivery_service.repository.ShipperIdentityProjectionRepository;
import com.delivery.identity.contracts.ShipperIdentityUpserted;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Applies the versioned Shipper identity mapping before Delivery API authorization. */
@Component
public class ShipperIdentityProjectionListener {
    private final ObjectMapper mapper; private final ShipperIdentityProjectionRepository projections;
    private final ShipperIdentityInboxReceiptRepository receipts;
    public ShipperIdentityProjectionListener(ObjectMapper mapper, ShipperIdentityProjectionRepository projections,
            ShipperIdentityInboxReceiptRepository receipts) {
        this.mapper = mapper; this.projections = projections; this.receipts = receipts;
    }
    @RetryableTopic(
            attempts = "${app.shipper.identity.retry.attempts:4}",
            backoff = @Backoff(delayExpression = "${app.shipper.identity.retry.initial-delay-ms:1000}",
                    multiplierExpression = "${app.shipper.identity.retry.multiplier:2.0}",
                    maxDelayExpression = "${app.shipper.identity.retry.max-delay-ms:10000}"),
            exclude = IllegalArgumentException.class,
            kafkaTemplate = "retryKafkaTemplate",
            autoCreateTopics = "${app.shipper.identity.retry.auto-create-topics:false}",
            retryTopicSuffix = "-retry-delivery-shipper-identity",
            dltTopicSuffix = ".delivery-shipper-identity.DLT")
    @KafkaListener(topics = "${app.shipper.identity-topic:shipper.identity.upserted}",
            groupId = "${app.shipper.identity-consumer-group:delivery-shipper-identity-v1}")
    @Transactional
    public void upsert(String raw) throws Exception {
        ShipperIdentityUpserted event = mapper.readValue(raw, ShipperIdentityUpserted.class);
        if (!ShipperIdentityUpserted.TYPE.equals(event.eventType()) || event.principalId() == null
                || event.legacyUserId() == null || event.shipperId() == null || event.mappingVersion() < 1) {
            throw new IllegalArgumentException("Invalid shipper.identity.upserted event");
        }
        String fingerprint = fingerprint(raw);
        ShipperIdentityInboxReceipt prior = receipts.findById(event.eventId()).orElse(null);
        if (prior != null) {
            if (!prior.getEventType().equals(event.eventType()) || !prior.getPrincipalId().equals(event.principalId())
                    || !prior.getPayloadFingerprint().equals(fingerprint)) throw new IllegalStateException("Conflicting shipper identity event reuse");
            return;
        }
        ShipperIdentityProjection projection = projections.findById(event.principalId()).orElse(null);
        if (projection != null && event.mappingVersion() < projection.getMappingVersion()) {
            saveReceipt(event, fingerprint); return;
        }
        if (projection == null) { projection = new ShipperIdentityProjection(); projection.setPrincipalId(event.principalId()); }
        if (projection.getMappingVersion() != null && event.mappingVersion() > projection.getMappingVersion() + 1) {
            throw new IllegalStateException("Shipper identity mapping version gap");
        }
        projection.setLegacyUserId(event.legacyUserId()); projection.setShipperId(event.shipperId());
        projection.setMappingVersion(event.mappingVersion()); projection.setUpdatedAt(LocalDateTime.now());
        projections.save(projection); saveReceipt(event, fingerprint);
    }
    private void saveReceipt(ShipperIdentityUpserted event, String fingerprint) {
        ShipperIdentityInboxReceipt receipt = new ShipperIdentityInboxReceipt(); receipt.setEventId(event.eventId());
        receipt.setEventType(event.eventType()); receipt.setPrincipalId(event.principalId());
        receipt.setPayloadFingerprint(fingerprint); receipt.setProcessedAt(LocalDateTime.now()); receipts.save(receipt);
    }
    private static String fingerprint(String raw) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new IllegalStateException("SHA-256 unavailable", failure); }
    }
}
