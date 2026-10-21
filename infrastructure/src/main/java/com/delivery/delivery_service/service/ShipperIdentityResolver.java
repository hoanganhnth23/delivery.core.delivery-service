package com.delivery.delivery_service.service;

import com.delivery.delivery_service.common.constants.RoleConstants;
import com.delivery.delivery_service.entity.ShipperIdentityProjection;
import com.delivery.delivery_service.exception.AccessDeniedException;
import com.delivery.delivery_service.metrics.BusinessMetrics;
import com.delivery.delivery_service.repository.ShipperIdentityProjectionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Resolves the canonical shipper identity at the resource-service boundary.
 *
 * <p>The JWT principal and the legacy profile user are deliberately kept as
 * separate inputs.  A projection is authoritative whenever it exists; the
 * legacy id is only a bounded compatibility fallback while the projection is
 * still being backfilled.</p>
 */
@Service
public final class ShipperIdentityResolver {

    private final ShipperIdentityProjectionRepository projections;
    private final BusinessMetrics metrics;
    private final boolean enforced;

    @Autowired
    public ShipperIdentityResolver(ShipperIdentityProjectionRepository projections,
                                   BusinessMetrics metrics,
                                   @Value("${app.shipper.identity-projection.enforced:false}") boolean enforced) {
        this(projections, metrics, enforced, true);
    }

    /** Compatibility instance for direct unit fixtures without a Spring context. */
    public static ShipperIdentityResolver compatibility(ShipperIdentityProjectionRepository projections,
                                                         BusinessMetrics metrics,
                                                         boolean enforced) {
        return new ShipperIdentityResolver(projections, metrics, enforced, false);
    }

    private ShipperIdentityResolver(ShipperIdentityProjectionRepository projections,
                                    BusinessMetrics metrics,
                                    boolean enforced,
                                    boolean productionConstructor) {
        this.projections = projections;
        this.metrics = metrics;
        this.enforced = enforced;
    }

    public Long resolveShipperId(Long principalId, Long legacyUserId, String role) {
        if (!RoleConstants.SHIPPER.equals(role)) {
            throw new AccessDeniedException("Chỉ shipper mới có thể thực hiện thao tác này");
        }
        if (!positive(principalId) || !positive(legacyUserId)) {
            throw new AccessDeniedException("Missing authenticated shipper identity");
        }

        if (projections != null) {
            Optional<ShipperIdentityProjection> byPrincipal = projections.findById(principalId);
            if (byPrincipal.isPresent()) {
                ShipperIdentityProjection mapping = byPrincipal.get();
                // A present but malformed/divergent projection is not a cache
                // miss. Falling back here could authorize the wrong shipper.
                if (!positive(mapping.getPrincipalId())
                        || !positive(mapping.getLegacyUserId())
                        || !positive(mapping.getShipperId())
                        || !principalId.equals(mapping.getPrincipalId())
                        || !legacyUserId.equals(mapping.getLegacyUserId())) {
                    throw new AccessDeniedException("Shipper identity projection is divergent");
                }
                return mapping.getShipperId();
            }

            // A mapping under the legacy key proves that the principal-side
            // projection is stale or inconsistent; do not reinterpret it as a
            // legacy-compatible row.
            Optional<ShipperIdentityProjection> byLegacy = projections.findByLegacyUserId(legacyUserId);
            if (byLegacy.isPresent()) {
                throw new AccessDeniedException("Shipper identity projection is divergent");
            }
        }

        if (enforced) {
            throw new AccessDeniedException("Shipper identity projection is not ready");
        }
        if (metrics != null) {
            metrics.identityLegacyFallback("shipper_mapping_missing");
        }
        return legacyUserId;
    }

    private boolean positive(Long value) {
        return value != null && value > 0;
    }
}
