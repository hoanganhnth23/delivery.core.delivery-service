package com.delivery.delivery.domain;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static com.delivery.delivery.domain.BatchRoutePolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class BatchRoutePolicyTest {
    private static Stop stop(Long delivery, Long order, Integer pickup, Integer dropoff) { return new Stop(delivery,order,pickup,dropoff); }
    private static void rejected(String message, Runnable rule) {
        var failure=assertThrows(OfferDecisionRejected.class,rule::run);
        assertEquals(OfferDecisionRejected.Kind.INVALID_STATUS,failure.kind()); assertEquals(message,failure.getMessage());
    }
    @Test void countsAndValidRoutes() {
        for(List<Stop> items:Arrays.asList(null,List.<Stop>of(),List.of(stop(1L,1L,0,1),stop(2L,2L,2,3),stop(3L,3L,4,5),stop(4L,4L,6,7)))) {
            rejected("Batch item count is invalid",()->validate(items)); rejected("Batch item count is invalid",()->validatePersisted(items));
        }
        var items=List.of(stop(1L,1L,0,2),stop(2L,2L,1,3));
        validate(items);validatePersisted(items);
        validate(List.of(stop(1L,1L,0,3),stop(2L,2L,1,4),stop(3L,3L,2,5)));
    }
    @Test void eventChecksEveryMalformedField() {
        List<Stop> malformed=Arrays.asList(null,stop(null,1L,0,1),stop(1L,null,0,1),stop(1L,1L,null,1),stop(1L,1L,0,null),
                stop(1L,1L,-1,1),stop(1L,1L,0,-1),stop(1L,1L,2,3),stop(1L,1L,0,2),stop(1L,1L,1,1));
        for(Stop item:malformed) rejected("Batch delivery IDs and stop sequences are invalid",()->validate(Arrays.asList(item)));
        Stop first=stop(1L,1L,0,2);
        for(Stop second:List.of(stop(1L,2L,1,3),stop(2L,1L,1,3),stop(2L,2L,0,3),stop(2L,2L,1,2)))
            rejected("Batch delivery IDs and stop sequences are invalid",()->validate(List.of(first,second)));
        rejected("Batch route sequences must cover every global stop exactly once",()->validate(List.of(stop(1L,1L,0,1),stop(2L,2L,1,3))));
    }
    @Test void defensiveGlobalCoverageChecksAlsoRejectImpossibleIntermediateSets() {
        // Earlier item checks guarantee both set sizes and bounds. Exercise the
        // retained defensive rule directly so no legacy guard is deleted.
        rejected("Batch route sequences must cover every global stop exactly once", () ->
                validateContiguous(java.util.Set.of(), java.util.Set.of(1), 2));
        rejected("Batch route sequences must cover every global stop exactly once", () ->
                validateContiguous(java.util.Set.of(0), java.util.Set.of(), 2));
        rejected("Batch route sequences must be contiguous", () ->
                validateContiguous(java.util.Set.of(0), java.util.Set.of(2), 2));
    }
    @Test void persistedChecksKeepLegacyNullSequenceFailure() {
        for(Stop item:Arrays.asList(null,stop(null,null,0,1),stop(1L,null,-1,1),stop(1L,null,0,-1),stop(1L,null,2,3),stop(1L,null,0,2),stop(1L,null,1,1)))
            rejected("Persisted batch item sequences are invalid",()->validatePersisted(Arrays.asList(item)));
        assertThrows(NullPointerException.class,()->validatePersisted(List.of(stop(1L,null,null,1))));
        assertThrows(NullPointerException.class,()->validatePersisted(List.of(stop(1L,null,0,null))));
        Stop first=stop(1L,null,0,2);
        for(Stop second:List.of(stop(1L,null,1,3),stop(2L,null,0,3),stop(2L,null,1,2)))
            rejected("Persisted batch item sequences are invalid",()->validatePersisted(List.of(first,second)));
        rejected("Batch route sequences must cover every global stop exactly once",()->validatePersisted(List.of(stop(1L,null,0,1),stop(2L,null,1,3))));
    }
}
