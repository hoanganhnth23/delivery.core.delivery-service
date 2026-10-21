package com.delivery.delivery_service.dto;

import com.delivery.delivery_service.dto.event.ShipperAcceptedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShipperAcceptedEventContractTest {

    @Test
    void acceptedEventContainsOnlyCanonicalSagaOrderFields() throws Exception {
        String json = new ObjectMapper().writeValueAsString(ShipperAcceptedEvent.builder()
                .orderId(20L)
                .deliveryId(1L)
                .shipperId(10L)
                .notes("leave at gate")
                .build());

        assertThat(json).contains("orderId", "deliveryId", "shipperId", "notes");
        assertThat(json).doesNotContain("matchId", "estimatedTime", "restaurantName",
                "shipperName", "customerName", "orderValue");
    }
}
