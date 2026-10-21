package com.delivery.delivery_service.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DeliveryApplicationPortConfigurationTest {

    @Test
    void legacyPortAdapterIsRemovedAfterMigration() {
        assertThatThrownBy(() -> Class.forName(
                "com.delivery.delivery_service.adapter.LegacyDeliveryPorts"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
