package com.delivery.delivery_service.service;

import com.delivery.delivery_service.entity.ShipperIdentityProjection;
import com.delivery.delivery_service.exception.AccessDeniedException;
import com.delivery.delivery_service.metrics.BusinessMetrics;
import com.delivery.delivery_service.repository.ShipperIdentityProjectionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShipperIdentityResolverTest {

    @Mock
    private ShipperIdentityProjectionRepository projections;

    @Mock
    private BusinessMetrics metrics;

    @Test
    void usesProjectionMappingEvenBeforeEnforcementIsEnabled() {
        ShipperIdentityProjection mapping = mapping(1007L, 107L, 7L);
        when(projections.findById(1007L)).thenReturn(Optional.of(mapping));

        ShipperIdentityResolver resolver = ShipperIdentityResolver.compatibility(projections, metrics, false);

        assertThat(resolver.resolveShipperId(1007L, 107L, "SHIPPER")).isEqualTo(7L);
    }

    @Test
    void fallsBackOnlyWhenProjectionIsMissingAndEnforcementIsOff() {
        when(projections.findById(1007L)).thenReturn(Optional.empty());
        when(projections.findByLegacyUserId(107L)).thenReturn(Optional.empty());

        ShipperIdentityResolver resolver = ShipperIdentityResolver.compatibility(projections, metrics, false);

        assertThat(resolver.resolveShipperId(1007L, 107L, "SHIPPER")).isEqualTo(107L);
        verify(metrics).identityLegacyFallback("shipper_mapping_missing");
    }

    @Test
    void rejectsDivergentProjectionInsteadOfUsingLegacyId() {
        when(projections.findById(1007L)).thenReturn(Optional.of(mapping(1007L, 999L, 7L)));

        ShipperIdentityResolver resolver = ShipperIdentityResolver.compatibility(projections, metrics, false);

        assertThrows(AccessDeniedException.class,
                () -> resolver.resolveShipperId(1007L, 107L, "SHIPPER"));
    }

    @Test
    void failsClosedWhenEnforcementIsEnabledAndProjectionIsMissing() {
        when(projections.findById(1007L)).thenReturn(Optional.empty());
        when(projections.findByLegacyUserId(107L)).thenReturn(Optional.empty());

        ShipperIdentityResolver resolver = ShipperIdentityResolver.compatibility(projections, metrics, true);

        assertThrows(AccessDeniedException.class,
                () -> resolver.resolveShipperId(1007L, 107L, "SHIPPER"));
    }

    private ShipperIdentityProjection mapping(Long principalId, Long legacyUserId, Long shipperId) {
        ShipperIdentityProjection mapping = new ShipperIdentityProjection();
        mapping.setPrincipalId(principalId);
        mapping.setLegacyUserId(legacyUserId);
        mapping.setShipperId(shipperId);
        mapping.setMappingVersion(1L);
        return mapping;
    }
}
