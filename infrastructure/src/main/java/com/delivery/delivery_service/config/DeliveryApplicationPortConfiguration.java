package com.delivery.delivery_service.config;

import com.delivery.delivery.application.api.DeliveryCommandPort;
import com.delivery.delivery.application.api.DeliveryQueryPort;
import com.delivery.delivery_service.adapter.DeliveryApplicationPorts;
import com.delivery.delivery_service.service.DeliveryBatchAcceptanceService;
import com.delivery.delivery_service.service.DeliveryBatchLifecycleService;
import com.delivery.delivery_service.service.DeliveryBatchSnapshotService;
import com.delivery.delivery_service.service.DeliveryExceptionService;
import com.delivery.delivery_service.service.DeliveryProofOfDeliveryService;
import com.delivery.delivery_service.service.DeliveryService;
import com.delivery.delivery_service.service.ShipperIdentityResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires legacy infrastructure services behind the framework-free inbound boundary. */
@Configuration
public class DeliveryApplicationPortConfiguration {
    @Bean
    DeliveryCommandPort deliveryCommandPort(DeliveryService delivery, DeliveryBatchAcceptanceService batches,
            DeliveryBatchLifecycleService lifecycle, ShipperIdentityResolver identities,
            DeliveryBatchSnapshotService snapshots, DeliveryProofOfDeliveryService proofs,
            DeliveryExceptionService exceptions) {
        return new DeliveryApplicationPorts(delivery, batches, lifecycle, identities, snapshots, proofs, exceptions);
    }

    @Bean
    DeliveryQueryPort deliveryQueryPort(DeliveryService delivery, DeliveryBatchAcceptanceService batches,
            DeliveryBatchLifecycleService lifecycle, ShipperIdentityResolver identities,
            DeliveryBatchSnapshotService snapshots, DeliveryProofOfDeliveryService proofs,
            DeliveryExceptionService exceptions) {
        return new DeliveryApplicationPorts(delivery, batches, lifecycle, identities, snapshots, proofs, exceptions);
    }
}
