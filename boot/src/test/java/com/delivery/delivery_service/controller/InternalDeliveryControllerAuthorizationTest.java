package com.delivery.delivery_service.controller;

import com.delivery.delivery_service.entity.Delivery;
import com.delivery.delivery_service.entity.DeliveryStatus;
import com.delivery.delivery_service.repository.DeliveryRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class InternalDeliveryControllerAuthorizationTest {

    private final DeliveryRepository repository = mock(DeliveryRepository.class);
    private final InternalDeliveryController controller =
            new InternalDeliveryController(repository, "shared-secret");

    @Test
    void failsClosedWithoutSharedSecret() {
        var response = controller.canTrack(1L, 7L, "USER", 42L, null);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody().getStatus()).isZero();
        verifyNoInteractions(repository);
    }

    @Test
    void customerCanTrackOnlyOwnActiveDeliveryAndAssignedShipper() {
        Delivery delivery = new Delivery();
        delivery.setId(1L);
        delivery.setCreatorId(7L);
        delivery.setShipperId(42L);
        delivery.setStatus(DeliveryStatus.ASSIGNED);
        when(repository.findById(1L)).thenReturn(Optional.of(delivery));

        assertThat(controller.canTrack(1L, 7L, "USER", 42L, "shared-secret").getBody().getData())
                .isEqualTo(Boolean.TRUE);
        assertThat(controller.canTrack(1L, 9L, "USER", 42L, "shared-secret").getBody().getData())
                .isEqualTo(Boolean.FALSE);
        assertThat(controller.canTrack(1L, 7L, "USER", 99L, "shared-secret").getBody().getData())
                .isEqualTo(Boolean.FALSE);
    }

    @Test
    void restaurantOwnerCanTrackOnlyOwnActiveDelivery() {
        Delivery delivery = new Delivery();
        delivery.setId(1L);
        delivery.setRestaurantOwnerId(70L);
        delivery.setShipperId(42L);
        delivery.setStatus(DeliveryStatus.DELIVERING);
        when(repository.findById(1L)).thenReturn(Optional.of(delivery));

        assertThat(controller.canTrack(1L, 70L, "SHOP_OWNER", 42L, "shared-secret")
                .getBody().getData()).isEqualTo(Boolean.TRUE);
        assertThat(controller.canTrack(1L, 71L, "SHOP_OWNER", 42L, "shared-secret")
                .getBody().getData()).isEqualTo(Boolean.FALSE);
    }

    @Test
    void terminalDeliveryCannotBeTracked() {
        Delivery delivery = new Delivery();
        delivery.setId(1L);
        delivery.setCreatorId(7L);
        delivery.setShipperId(42L);
        delivery.setStatus(DeliveryStatus.DELIVERED);
        when(repository.findById(1L)).thenReturn(Optional.of(delivery));

        assertThat(controller.canTrack(1L, 7L, "USER", 42L, "shared-secret").getBody().getData())
                .isEqualTo(Boolean.FALSE);
    }

    @Test
    void returnsOnlySimulationDeliveryIdentityAndStatusForSharedSecret() {
        UUID runId = UUID.randomUUID();
        Delivery delivery = new Delivery();
        delivery.setId(9L);
        delivery.setOrderId(17L);
        delivery.setStatus(DeliveryStatus.DELIVERED);
        when(repository.findBySimulationRunIdOrderByIdAsc(runId)).thenReturn(List.of(delivery));

        var response = controller.findSimulationRunDeliveries(runId, "shared-secret");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().getData()).containsExactly(
                new InternalDeliveryController.SimulationDeliveryStatus(9L, 17L, "DELIVERED"));
    }
}
