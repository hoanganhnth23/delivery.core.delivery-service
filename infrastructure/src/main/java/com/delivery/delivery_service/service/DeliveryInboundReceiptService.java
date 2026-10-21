package com.delivery.delivery_service.service;

import com.delivery.delivery_service.entity.DeliveryInboundReceipt;
import com.delivery.delivery_service.repository.DeliveryInboundReceiptRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Owns exact-replay and contradictory-replay fencing for Saga→Delivery commands. */
@Service
public class DeliveryInboundReceiptService {

    private final DeliveryInboundReceiptRepository repository;

    @Value("${spring.datasource.url:}")
    private String dataSourceUrl;

    public DeliveryInboundReceiptService(DeliveryInboundReceiptRepository repository) {
        this.repository = repository;
    }

    /**
     * @return {@code true} only for a command that has not yet been committed.
     * An exact replay returns {@code false}; a reused identity with a different
     * command or payload fails closed.
     */
    @Transactional
    public boolean claim(UUID eventId, String commandType, Long orderId,
                         Long deliveryId, String rawPayload) {
        require(eventId != null, "eventId is required");
        requireText(commandType, "commandType");
        require(orderId != null && orderId > 0, "orderId must be positive");
        requireText(rawPayload, "raw command payload");

        String fingerprint = fingerprint(rawPayload);
        DeliveryInboundReceipt existing = repository.findById(eventId).orElse(null);
        if (existing == null) {
            // Do not use saveAndFlush followed by a duplicate-key exception:
            // a concurrent Kafka replica needs to converge to exact replay,
            // including when the first transaction commits while the second
            // is waiting on the primary-key conflict.
            if (insertIfAbsent(eventId, commandType, orderId, deliveryId, fingerprint) == 1) {
                return true;
            }
            existing = repository.findById(eventId).orElseThrow(() ->
                    new IllegalStateException("delivery command receipt conflict resolved without a committed row"));
        }
        requireExactReplay(existing, commandType, orderId, deliveryId, fingerprint);
        return false;
    }

    private int insertIfAbsent(UUID eventId, String commandType, Long orderId,
                               Long deliveryId, String fingerprint) {
        if (dataSourceUrl != null && dataSourceUrl.startsWith("jdbc:h2:")) {
            return repository.insertIfAbsentH2(eventId, commandType, orderId, deliveryId, fingerprint);
        }
        return repository.insertIfAbsentPostgres(eventId, commandType, orderId, deliveryId, fingerprint);
    }

    private void requireExactReplay(DeliveryInboundReceipt existing, String commandType,
                                    Long orderId, Long deliveryId, String fingerprint) {
        if (!existing.getCommandType().equals(commandType)
                || !existing.getOrderId().equals(orderId)
                || !java.util.Objects.equals(existing.getDeliveryId(), deliveryId)
                || !existing.getPayloadFingerprint().equals(fingerprint)) {
            throw new IllegalArgumentException(
                    "delivery command eventId replay has contradictory command identity or payload");
        }
    }

    private String fingerprint(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
