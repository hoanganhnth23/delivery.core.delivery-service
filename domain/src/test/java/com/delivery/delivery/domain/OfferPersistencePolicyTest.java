package com.delivery.delivery.domain;

import com.delivery.delivery.domain.OfferPersistencePolicy.CacheDecision;
import com.delivery.delivery.domain.OfferPersistencePolicy.ExpiryDecision;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

import static com.delivery.delivery.domain.DeliveryStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class OfferPersistencePolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 9, 0);

    @Test
    void legacySessionAndDeadlineRules() {
        UUID id = UUID.randomUUID();
        assertEquals(UUID.nameUUIDFromBytes(("legacy-offer:" + id).getBytes(StandardCharsets.UTF_8)).toString(),
                OfferPersistencePolicy.legacySession(id));
        assertEquals(NOW.plusSeconds(180), OfferPersistencePolicy.expiresAt(null, null, NOW));
        assertEquals(NOW.plusSeconds(1), OfferPersistencePolicy.expiresAt(NOW, 0, NOW));
        assertEquals(NOW.plusSeconds(180), OfferPersistencePolicy.expiresAt(NOW, 999, NOW));
        assertEquals("Shipper offer already expired", assertThrows(OfferDecisionRejected.class,
                () -> OfferPersistencePolicy.expiresAt(NOW.minusSeconds(60), 60, NOW)).getMessage());
        assertTrue(OfferPersistencePolicy.sameDeadline(NOW, NOW.plusNanos(1_000_000)));
        assertFalse(OfferPersistencePolicy.sameDeadline(NOW, NOW.plusNanos(1_000_001)));
        assertTrue(OfferPersistencePolicy.sameDeadline(null, null));
        assertFalse(OfferPersistencePolicy.sameDeadline(NOW, null));
        assertFalse(OfferPersistencePolicy.sameDeadline(null, NOW));
    }

    @Test
    void foundShipperIsAppliedWhileFindingOrReplacingAnExpiredOffer() {
        LocalDateTime expires = NOW.plusSeconds(60);
        assertEquals(CacheDecision.APPLY,
                OfferPersistencePolicy.onShipperFound(FINDING_SHIPPER, null, null, 9, expires, NOW));
        assertEquals(CacheDecision.APPLY,
                OfferPersistencePolicy.onShipperFound(WAIT_SHIPPER_CONFIRM, 8L, NOW, 9, expires, NOW));
        assertEquals(CacheDecision.REPLAY,
                OfferPersistencePolicy.onShipperFound(WAIT_SHIPPER_CONFIRM, 9L, expires, 9, expires, NOW));
    }

    @Test
    void foundShipperConflictsAreRejected() {
        LocalDateTime expires = NOW.plusSeconds(60);
        assertEquals("Delivery already has an active shipper offer", assertThrows(OfferDecisionRejected.class,
                () -> OfferPersistencePolicy.onShipperFound(WAIT_SHIPPER_CONFIRM, 8L, expires, 9, expires, NOW))
                .getMessage());
        assertEquals("Persisted offer has contradictory delivery status", assertThrows(OfferDecisionRejected.class,
                () -> OfferPersistencePolicy.onShipperFound(ASSIGNED, 9L, expires, 9, expires, NOW)).getMessage());
        assertEquals("Delivery is no longer finding a shipper", assertThrows(OfferDecisionRejected.class,
                () -> OfferPersistencePolicy.onShipperFound(ASSIGNED, null, null, 9, expires, NOW)).getMessage());
        assertThrows(OfferDecisionRejected.class,
                () -> OfferPersistencePolicy.onShipperFound(WAIT_SHIPPER_CONFIRM, 9L, expires.plusSeconds(5), 9,
                        expires, NOW));
        assertEquals(CacheDecision.APPLY,
                OfferPersistencePolicy.onShipperFound(FINDING_SHIPPER, 8L, null, 9, expires, NOW));
    }

    @Test
    void expiryReportsStrongerStatesAndFencesStaleCommands() {
        LocalDateTime deadline = NOW.minusSeconds(1);
        assertEquals(ExpiryDecision.ASSIGNED, OfferPersistencePolicy.onExpire(ASSIGNED, "s", "s", 9L, deadline, 9, deadline, NOW));
        assertEquals(ExpiryDecision.TERMINAL, OfferPersistencePolicy.onExpire(CANCELLED, "s", "s", 9L, deadline, 9, deadline, NOW));
        assertEquals(ExpiryDecision.TERMINAL, OfferPersistencePolicy.onExpire(SHIPPER_NOT_FOUND, null, null, null, null, 9, deadline, NOW));
        assertEquals(ExpiryDecision.STALE_SESSION, OfferPersistencePolicy.onExpire(WAIT_SHIPPER_CONFIRM, "old", "new", 9L, deadline, 9, deadline, NOW));
        assertEquals(ExpiryDecision.ALREADY_RETIRED, OfferPersistencePolicy.onExpire(FINDING_SHIPPER, "s", null, null, null, 9, deadline, NOW));
        assertEquals(ExpiryDecision.STALE_COMMAND, OfferPersistencePolicy.onExpire(WAIT_SHIPPER_CONFIRM, null, "s", 8L, deadline, 9, deadline, NOW));
        assertEquals(ExpiryDecision.STALE_COMMAND, OfferPersistencePolicy.onExpire(WAIT_SHIPPER_CONFIRM, " ", "s", null, deadline, 9, deadline, NOW));
        assertEquals(ExpiryDecision.STALE_COMMAND, OfferPersistencePolicy.onExpire(WAIT_SHIPPER_CONFIRM, "s", "s", 9L, deadline, 9, NOW, NOW));
        assertEquals(ExpiryDecision.STALE_COMMAND, OfferPersistencePolicy.onExpire(FINDING_SHIPPER, "s", "s", 9L, deadline, 9, deadline, NOW));
        assertEquals(ExpiryDecision.EXPIRE, OfferPersistencePolicy.onExpire(WAIT_SHIPPER_CONFIRM, "s", "s", 9L, deadline, 9, deadline, NOW));
        assertEquals(ExpiryDecision.EXPIRE, OfferPersistencePolicy.onExpire(WAIT_SHIPPER_CONFIRM, null, null, 9L, deadline, 9, deadline, NOW));
        LocalDateTime future = NOW.plusSeconds(5);
        assertEquals("Cannot expire a shipper offer before its deadline", assertThrows(OfferDecisionRejected.class,
                () -> OfferPersistencePolicy.onExpire(WAIT_SHIPPER_CONFIRM, "s", "s", 9L, future, 9, future, NOW)).getMessage());
    }

    @Test
    void retirementOutcomeNamesTheReportedState() {
        assertEquals("ASSIGNED", OfferPersistencePolicy.retirementOutcome(ExpiryDecision.ASSIGNED));
        assertEquals("TERMINAL", OfferPersistencePolicy.retirementOutcome(ExpiryDecision.TERMINAL));
        for (ExpiryDecision retired : new ExpiryDecision[] {ExpiryDecision.STALE_SESSION, ExpiryDecision.ALREADY_RETIRED,
                ExpiryDecision.STALE_COMMAND, ExpiryDecision.EXPIRE}) {
            assertEquals("RETIRED", OfferPersistencePolicy.retirementOutcome(retired));
        }
    }
}
