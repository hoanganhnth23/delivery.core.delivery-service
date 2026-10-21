package com.delivery.delivery.domain;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static com.delivery.delivery.domain.DeliveryReadPolicy.*;

class DeliveryReadPolicyTest {
    @Test void exhaustiveNullableOwnershipMatrixMatchesOriginalReadPredicate() {
        Long[] ids={null,1L,2L};
        String[] roles={null,"ADMIN","SHIPPER","USER","SHOP_OWNER","OTHER","shipper"};
        for(String role:roles) for(Long principal:ids) for(Long legacy:ids)
            for(Long storedPrincipal:ids) for(Long storedLegacy:ids) {
                AtomicInteger calls=new AtomicInteger();
                Supplier<Long> shipper=()->{calls.incrementAndGet();return 1L;};
                boolean owned=(storedPrincipal != null && principal != null && principal.equals(storedPrincipal))
                        || (storedPrincipal == null && legacy != null && legacy.equals(storedLegacy));
                boolean allowed="ADMIN".equals(role) || "SHIPPER".equals(role) && Long.valueOf(1).equals(storedLegacy)
                        || ("USER".equals(role) || "SHOP_OWNER".equals(role)) && owned;
                if(allowed) {
                    String fallback=requireView(role,principal,legacy,storedPrincipal,storedLegacy,
                            storedPrincipal,storedLegacy,storedLegacy,shipper);
                    String expected=storedPrincipal == null && "USER".equals(role) ? "customer_read"
                            : storedPrincipal == null && "SHOP_OWNER".equals(role) ? "restaurant_owner_read" : null;
                    assertEquals(expected,fallback);
                } else {
                    var error=assertThrows(OfferDecisionRejected.class,()->requireView(role,principal,legacy,
                            storedPrincipal,storedLegacy,storedPrincipal,storedLegacy,storedLegacy,shipper));
                    assertEquals(OfferDecisionRejected.Kind.ACCESS_DENIED,error.kind());
                    assertEquals("Bạn không có quyền xem thông tin giao hàng này",error.getMessage());
                }
                assertEquals("SHIPPER".equals(role)?1:0,calls.get());
            }
    }
    @Test void lazyShipperResolutionKeepsFailureAndListSelectionOrder() {
        RuntimeException failure=new RuntimeException("projection failure");
        Supplier<Long> shipper=()->{throw failure;};
        assertSame(failure,assertThrows(RuntimeException.class,()->requireView("SHIPPER",null,null,null,null,null,null,null,shipper)));
        assertSame(failure,assertThrows(RuntimeException.class,()->listActor("SHIPPER",2L,shipper)));
        assertEquals(2L,listActor("USER",2L,shipper));
        assertNull(listActor(null,null,shipper));
        assertEquals(3L,listActor("SHIPPER",2L,()->3L));
    }
    @Test void listAndCurrentOfferAuthorizationMatrix() {
        for(String role:new String[]{null,"ADMIN","SHIPPER","USER","SHOP_OWNER","OTHER"})
            for(Long requested:new Long[]{null,-1L,0L,1L,2L}) for(Long actor:new Long[]{null,-1L,0L,1L,2L}) {
                boolean allowed="ADMIN".equals(role) || "SHIPPER".equals(role) && requested != null && requested.equals(actor);
                if(allowed) requireShipperList(requested,actor,role);
                else {
                    var error=assertThrows(OfferDecisionRejected.class,()->requireShipperList(requested,actor,role));
                    assertEquals(OfferDecisionRejected.Kind.ACCESS_DENIED,error.kind());
                    assertEquals("Bạn không có quyền xem danh sách delivery này",error.getMessage());
                }
            }
        for(String role:new String[]{null,"ADMIN","SHIPPER","USER"}) for(Long id:new Long[]{null,-1L,0L,1L}) {
            if("SHIPPER".equals(role) && id != null && id > 0) requireCurrentOffer(id,role);
            else {
                var error=assertThrows(OfferDecisionRejected.class,()->requireCurrentOffer(id,role));
                assertEquals("SHIPPER".equals(role)?OfferDecisionRejected.Kind.INVALID_STATUS:OfferDecisionRejected.Kind.ACCESS_DENIED,error.kind());
                assertEquals("SHIPPER".equals(role)?"Shipper ID is required":"Chỉ shipper mới có thể xem offer hiện tại",error.getMessage());
            }
        }
        requireSingleOffer(0);requireSingleOffer(1);
        var error=assertThrows(OfferDecisionRejected.class,()->requireSingleOffer(2));
        assertEquals(OfferDecisionRejected.Kind.INVALID_STATUS,error.kind());
        assertEquals("Shipper has multiple active offers",error.getMessage());
    }
}
