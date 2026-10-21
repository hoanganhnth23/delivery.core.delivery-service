package com.delivery.delivery_service.service;

import com.delivery.delivery_service.entity.ShipperIdentityProjection;
import com.delivery.delivery_service.entity.ShipperIdentityInboxReceipt;
import com.delivery.delivery_service.repository.*;
import com.delivery.identity.contracts.ShipperIdentityUpserted;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShipperIdentityProjectionListenerTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final ShipperIdentityProjectionRepository projections = mock(ShipperIdentityProjectionRepository.class);
    final ShipperIdentityInboxReceiptRepository receipts = mock(ShipperIdentityInboxReceiptRepository.class);
    final ShipperIdentityProjectionListener listener = new ShipperIdentityProjectionListener(mapper,projections,receipts);
    final UUID eventId = UUID.randomUUID();
    String event(long version) throws Exception {
        return mapper.writeValueAsString(new ShipperIdentityUpserted(eventId,ShipperIdentityUpserted.TYPE,1,
                Instant.parse("2026-01-01T00:00:00Z"),null,null,10L,100L,7L,version));
    }
    ShipperIdentityProjection existing(long version) {
        var p = new ShipperIdentityProjection(); p.setPrincipalId(10L); p.setLegacyUserId(100L);
        p.setShipperId(5L); p.setMappingVersion(version);
        when(projections.findById(10L)).thenReturn(Optional.of(p)); return p;
    }
    @Test void firstMappingIsPersistedBeforeReceiptAndExactReplayIsNoOp() throws Exception {
        String raw = event(1); listener.upsert(raw);
        var projection = ArgumentCaptor.forClass(ShipperIdentityProjection.class);
        var receipt = ArgumentCaptor.forClass(ShipperIdentityInboxReceipt.class);
        var order = inOrder(projections,receipts);
        order.verify(receipts).findById(eventId); order.verify(projections).findById(10L);
        order.verify(projections).save(projection.capture()); order.verify(receipts).save(receipt.capture());
        assertThat(projection.getValue().getShipperId()).isEqualTo(7L);
        assertThat(projection.getValue().getLegacyUserId()).isEqualTo(100L);
        assertThat(projection.getValue().getMappingVersion()).isEqualTo(1L);
        assertThat(receipt.getValue().getPayloadFingerprint()).hasSize(64);
        assertThat(receipt.getValue().getProcessedAt()).isNotNull();
        when(receipts.findById(eventId)).thenReturn(Optional.of(receipt.getValue()));
        clearInvocations(projections,receipts); listener.upsert(raw);
        verifyNoInteractions(projections); verify(receipts,never()).save(any());
        assertThatThrownBy(() -> listener.upsert(event(2))).isInstanceOf(IllegalStateException.class)
                .hasMessage("Conflicting shipper identity event reuse");
    }
    @Test void oldVersionRecordsReceiptWithoutRegressingMapping() throws Exception {
        var p = existing(3); listener.upsert(event(2));
        assertThat(p.getShipperId()).isEqualTo(5L); assertThat(p.getMappingVersion()).isEqualTo(3L);
        verify(projections,never()).save(any()); verify(receipts).save(any());
    }
    @Test void versionGapFailsWithoutMutationButNextVersionUpdates() throws Exception {
        var p = existing(1);
        assertThatThrownBy(() -> listener.upsert(event(3))).hasMessage("Shipper identity mapping version gap");
        verify(projections,never()).save(any()); verify(receipts,never()).save(any());
        listener.upsert(event(2));
        assertThat(p.getMappingVersion()).isEqualTo(2L); assertThat(p.getShipperId()).isEqualTo(7L);
        verify(projections).save(p); verify(receipts).save(any());
    }
    @Test void invalidVersionAndTypeFailBeforePersistence() throws Exception {
        assertThatThrownBy(() -> listener.upsert(event(0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> listener.upsert(event(1).replace(ShipperIdentityUpserted.TYPE,"wrong")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(projections,receipts);
    }
}
