package com.delivery.delivery_service.controller;

import com.delivery.auth.resourceserver.security.AuthenticatedActor;
import com.delivery.delivery.application.api.DeliveryCommandPort;
import com.delivery.delivery.application.api.DeliveryQueryPort;
import com.delivery.delivery_service.dto.request.AcceptDeliveryRequest;
import com.delivery.delivery_service.dto.response.DeliveryOfferResponse;
import com.delivery.delivery_service.dto.response.DeliveryResponse;
import java.lang.reflect.Field;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliveryControllerPortBoundaryTest {
    @Test
    void controllerUsesInboundPortsAndPreservesActorIdentity() {
        DeliveryCommandPort commands = mock(DeliveryCommandPort.class);
        DeliveryQueryPort queries = mock(DeliveryQueryPort.class);
        DeliveryResponse accepted = new DeliveryResponse();
        when(commands.acceptDelivery(any())).thenReturn(accepted);
        AcceptDeliveryRequest request = new AcceptDeliveryRequest();
        request.setOrderId(77L); request.setAction("ACCEPT"); request.setNotes("ready");
        AuthenticatedActor actor = new AuthenticatedActor(10L, 100L, "shipper@example.com", Set.of("SHIPPER"));

        var response = new DeliveryController(commands, queries).acceptDelivery(request, actor);

        verify(commands).acceptDelivery(new DeliveryCommandPort.AcceptDeliveryCommand(77L, "ACCEPT", "ready", null,
                null, null, null, new DeliveryCommandPort.Actor(10L, 100L, "SHIPPER", actor.getSimulationContext())));
        assertThat(response.getBody().getData()).isSameAs(accepted);
    }

    @Test
    void queryPortKeepsNullOfferAsSuccessfulRecoveryResponse() {
        DeliveryCommandPort commands = mock(DeliveryCommandPort.class);
        DeliveryQueryPort queries = mock(DeliveryQueryPort.class);
        when(queries.currentOffer(any())).thenReturn(null);
        AuthenticatedActor actor = new AuthenticatedActor(10L, "shipper@example.com", Set.of("SHIPPER"));

        var response = new DeliveryController(commands, queries).getCurrentOffer(actor);

        verify(queries).currentOffer(new DeliveryCommandPort.Actor(10L, actor.getLegacyUserId(), "SHIPPER", actor.getSimulationContext()));
        assertThat(response.getBody().getStatus()).isEqualTo(1);
        assertThat(response.getBody().getData()).isNull();
    }

    @Test
    void nullActorIsRejectedBeforePortInteractionAndNoLegacyServiceFieldsRemain() {
        DeliveryCommandPort commands = mock(DeliveryCommandPort.class);
        DeliveryQueryPort queries = mock(DeliveryQueryPort.class);
        var controller = new DeliveryController(commands, queries);
        assertThatThrownBy(() -> controller.getCurrentOffer(null)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        for (Field field : DeliveryController.class.getDeclaredFields()) {
            assertThat(field.getType().getName()).doesNotContain("delivery_service.service");
        }
    }
}
