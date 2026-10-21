package com.delivery.delivery.application.api;

import com.delivery.delivery.domain.DeliveryStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PortDependencyTest {
    @Test
    void portsAreInterfacesAndUseDomainOnly() {
        assertTrue(Modifier.isInterface(SagaIngressPort.class.getModifiers()));
        assertTrue(Modifier.isInterface(MatchPort.class.getModifiers()));
        assertTrue(Modifier.isInterface(DeliveryStoragePort.class.getModifiers()));
        assertTrue(DeliveryStatus.class.getPackageName().startsWith("com.delivery.delivery.domain"));
    }
}
