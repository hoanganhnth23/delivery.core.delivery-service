package com.delivery.delivery.domain;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static com.delivery.delivery.domain.DeliveryAccessPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryAccessPolicyTest {
    @Test void principalIdentityWinsAndLegacyOnlyAppliesToUnmigratedRows() {
        assertTrue(isRestaurantOwner(1L, 2L, 1L, 9L));
        assertFalse(isRestaurantOwner(1L, 2L, 9L, 2L));
        assertTrue(isRestaurantOwner(1L, 2L, null, 2L));
        assertFalse(isRestaurantOwner(1L, 2L, null, null));
        assertFalse(isRestaurantOwner(1L, 2L, null, 9L));
        assertTrue(isCustomer(1L, 2L, 1L, 9L));
        assertFalse(isCustomer(1L, 2L, 9L, 2L));
        assertTrue(isCustomer(1L, 2L, null, 2L));
        assertFalse(isCustomer(1L, 2L, null, 9L));
        assertFalse(isCustomer(null, null, 1L, 2L));
        assertFalse(isRestaurantOwner(null, null, 1L, 2L));
        assertThrows(NullPointerException.class, () -> isCustomer(1L, 2L, null, null));
    }
    @Test void viewerRulesKeepShipperResolutionLazyAndPreserveItsFailure() {
        AtomicInteger calls = new AtomicInteger();
        Runnable assigned = calls::incrementAndGet;
        for (Viewer viewer : new Viewer[]{Viewer.ADMIN, Viewer.CUSTOMER, Viewer.RESTAURANT_OWNER})
            requireViewer(viewer, 1L, 2L, 1L, 2L, 1L, 2L, assigned, "denied");
        assertEquals(0, calls.get());
        requireViewer(Viewer.SHIPPER, null, null, null, null, null, null, assigned, "denied");
        assertEquals(1, calls.get());
        RuntimeException failure = new RuntimeException("identity resolution failed");
        assertSame(failure, assertThrows(RuntimeException.class, () -> requireViewer(Viewer.SHIPPER,
                null,null,null,null,null,null, () -> {throw failure;}, "denied")));
        for (Viewer viewer : new Viewer[]{Viewer.CUSTOMER,Viewer.RESTAURANT_OWNER,Viewer.OTHER}) {
            var denied = assertThrows(OfferDecisionRejected.class, () -> requireViewer(viewer,1L,2L,9L,9L,9L,9L,assigned,"denied"));
            assertEquals(OfferDecisionRejected.Kind.ACCESS_DENIED, denied.kind()); assertEquals("denied",denied.getMessage());
        }
        requireViewer(Viewer.CUSTOMER,1L,2L,null,2L,null,null,assigned,"denied");
        requireViewer(Viewer.RESTAURANT_OWNER,1L,2L,null,null,null,2L,assigned,"denied");
        assertEquals(1, calls.get());
    }
}
