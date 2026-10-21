package com.delivery.delivery_service.controller;

import com.delivery.auth.resourceserver.security.AuthenticatedActor;
import com.delivery.delivery_service.dto.response.DeliveryOfferResponse;
import com.delivery.delivery_service.dto.request.AcceptBatchRequest;
import com.delivery.delivery_service.dto.response.DeliveryResponse;
import com.delivery.delivery_service.service.DeliveryBatchAcceptanceService;
import com.delivery.delivery_service.service.DeliveryBatchLifecycleService;
import com.delivery.delivery_service.service.DeliveryBatchSnapshotService;
import com.delivery.delivery_service.service.ShipperIdentityResolver;
import com.delivery.delivery_service.service.DeliveryService;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliveryControllerOfferAuthorizationTest {

    @Test
    void currentOfferUsesTrustedShipperIdentityWithoutPathOverride() {
        DeliveryService service = mock(DeliveryService.class);
        DeliveryOfferResponse offer = new DeliveryOfferResponse();
        offer.setOrderId(20L);
        when(service.getCurrentOffer(10L, "SHIPPER")).thenReturn(offer);
        AuthenticatedActor actor = new AuthenticatedActor(10L, "shipper@example.com", Set.of("SHIPPER"));

        var response = new DeliveryController(service).getCurrentOffer(actor);

        verify(service).getCurrentOffer(10L, "SHIPPER");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).isSameAs(offer);
    }

    @Test
    void noOfferRemainsAStableSuccessfulRecoveryResponse() {
        DeliveryService service = mock(DeliveryService.class);
        when(service.getCurrentOffer(10L, "SHIPPER")).thenReturn(null);
        AuthenticatedActor actor = new AuthenticatedActor(10L, "shipper@example.com", Set.of("SHIPPER"));

        var response = new DeliveryController(service).getCurrentOffer(actor);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(1);
        assertThat(response.getBody().getData()).isNull();
    }

    @Test
    void batchAcceptReceivesBothAuthAndLegacyIdentityForCanonicalResolution() {
        DeliveryService service = mock(DeliveryService.class);
        DeliveryBatchAcceptanceService batches = mock(DeliveryBatchAcceptanceService.class);
        DeliveryBatchLifecycleService lifecycle = mock(DeliveryBatchLifecycleService.class);
        ShipperIdentityResolver resolver = ShipperIdentityResolver.compatibility(null, null, false);
        DeliveryBatchSnapshotService snapshots = mock(DeliveryBatchSnapshotService.class);
        AcceptBatchRequest request = new AcceptBatchRequest();
        request.setBatchId(UUID.randomUUID());
        DeliveryResponse accepted = new DeliveryResponse();
        when(batches.accept(request, 1007L, 107L, "SHIPPER")).thenReturn(accepted);
        AuthenticatedActor actor = new AuthenticatedActor(1007L, 107L, "shipper@example.com", Set.of("SHIPPER"));

        var response = new DeliveryController(service, batches, lifecycle, resolver, snapshots)
                .acceptBatch(request, actor);

        verify(batches).accept(request, 1007L, 107L, "SHIPPER");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).isSameAs(accepted);
    }
}
