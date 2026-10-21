package com.delivery.delivery_service.service;

import com.delivery.delivery_service.entity.DeliveryInboundReceipt;
import com.delivery.delivery_service.repository.DeliveryInboundReceiptRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;

class DeliveryInboundReceiptServiceTest {

    private static final UUID EVENT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Test
    void firstCommandIsFlushedBeforeItsDeliverySideEffect() {
        DeliveryInboundReceiptRepository repository = mock(DeliveryInboundReceiptRepository.class);
        when(repository.findById(EVENT_ID)).thenReturn(Optional.empty());
        when(repository.insertIfAbsentPostgres(any(), anyString(), anyLong(), any(), anyString())).thenReturn(1);
        DeliveryInboundReceiptService service = new DeliveryInboundReceiptService(repository);

        assertThat(service.claim(EVENT_ID, "CACHE_SHIPPER_OFFER", 101L, 202L,
                "{\"eventId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\"}"))
                .isTrue();

        verify(repository).insertIfAbsentPostgres(eq(EVENT_ID), eq("CACHE_SHIPPER_OFFER"), eq(101L), eq(202L),
                argThat(fingerprint -> fingerprint.length() == 64));
    }

    @Test
    void exactReplayIsAnAcknowledgedNoOp() {
        String payload = "{\"eventId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\"}";
        DeliveryInboundReceiptRepository repository = mock(DeliveryInboundReceiptRepository.class);
        DeliveryInboundReceipt receipt = new DeliveryInboundReceipt(
                EVENT_ID, "CACHE_SHIPPER_OFFER", 101L, 202L, sha256(payload));
        when(repository.findById(EVENT_ID)).thenReturn(Optional.of(receipt));
        DeliveryInboundReceiptService service = new DeliveryInboundReceiptService(repository);

        assertThat(service.claim(EVENT_ID, "CACHE_SHIPPER_OFFER", 101L, 202L, payload)).isFalse();
    }

    @Test
    void sameIdentityWithDifferentPayloadFailsClosed() {
        DeliveryInboundReceiptRepository repository = mock(DeliveryInboundReceiptRepository.class);
        DeliveryInboundReceipt receipt = new DeliveryInboundReceipt(
                EVENT_ID, "CACHE_SHIPPER_OFFER", 101L, 202L, sha256("{\"candidate\":1}"));
        when(repository.findById(EVENT_ID)).thenReturn(Optional.of(receipt));
        DeliveryInboundReceiptService service = new DeliveryInboundReceiptService(repository);

        assertThatThrownBy(() -> service.claim(EVENT_ID, "CACHE_SHIPPER_OFFER", 101L, 202L,
                "{\"candidate\":2}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contradictory");
    }

    private String sha256(String payload) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
