package com.delivery.delivery.domain;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static com.delivery.delivery.domain.OfferDecisionRejected.Kind.INVALID_STATUS;

/**
 * Delivery owns the single active offer: persisting a found shipper,
 * confirming exact replays, and retiring an expired offer only for the exact
 * shipper, deadline and generation the coordinator observed.
 */
public final class OfferPersistencePolicy {

    public static final int MAX_OFFER_SECONDS = 180;

    private OfferPersistencePolicy() {
    }

    /** Generation identity for legacy commands that carried no matching session. */
    public static String legacySession(UUID commandEventId) {
        return UUID.nameUUIDFromBytes(("legacy-offer:" + commandEventId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** Offer deadline = foundAt (or now) + waiting timeout clamped to 1..180 s; must still be in the future. */
    public static LocalDateTime expiresAt(LocalDateTime foundAt, Integer waitingTimeoutSeconds, LocalDateTime now) {
        int timeout = waitingTimeoutSeconds == null
                ? MAX_OFFER_SECONDS
                : Math.max(1, Math.min(waitingTimeoutSeconds, MAX_OFFER_SECONDS));
        LocalDateTime expiresAt = (foundAt == null ? now : foundAt).plusSeconds(timeout);
        if (!expiresAt.isAfter(now)) {
            throw new OfferDecisionRejected(INVALID_STATUS, "Shipper offer already expired");
        }
        return expiresAt;
    }

    /** PostgreSQL may round sub-millisecond precision; deadlines within 1 ms are the same. */
    public static boolean sameDeadline(LocalDateTime first, LocalDateTime second) {
        if (first == null || second == null) {
            return first == second;
        }
        return Math.abs(Duration.between(first, second).toNanos()) <= 1_000_000L;
    }

    public enum CacheDecision { APPLY, REPLAY }

    public static CacheDecision onShipperFound(DeliveryStatus status, Long currentOfferedShipperId,
                                               LocalDateTime currentOfferExpiresAt, long offeredShipperId,
                                               LocalDateTime expiresAt, LocalDateTime now) {
        boolean replacingExpiredOffer = status == DeliveryStatus.WAIT_SHIPPER_CONFIRM
                && currentOfferExpiresAt != null && !currentOfferExpiresAt.isAfter(now);
        if (currentOfferedShipperId != null && currentOfferedShipperId != offeredShipperId
                && currentOfferExpiresAt != null && currentOfferExpiresAt.isAfter(now)) {
            throw new OfferDecisionRejected(INVALID_STATUS, "Delivery already has an active shipper offer");
        }
        if (currentOfferedShipperId != null && currentOfferedShipperId == offeredShipperId
                && sameDeadline(expiresAt, currentOfferExpiresAt)) {
            if (status != DeliveryStatus.WAIT_SHIPPER_CONFIRM) {
                throw new OfferDecisionRejected(INVALID_STATUS, "Persisted offer has contradictory delivery status");
            }
            return CacheDecision.REPLAY;
        }
        if (status != DeliveryStatus.FINDING_SHIPPER && !replacingExpiredOffer) {
            throw new OfferDecisionRejected(INVALID_STATUS, "Delivery is no longer finding a shipper");
        }
        return CacheDecision.APPLY;
    }

    public enum ExpiryDecision {
        /** Acceptance committed first; report it. */
        ASSIGNED,
        /** Delivery is cancelled or terminally unmatched. */
        TERMINAL,
        /** A delayed timeout for an older generation; it fenced its own session only. */
        STALE_SESSION,
        /** The offer was already cleared by an earlier identical timeout. */
        ALREADY_RETIRED,
        /** The command no longer matches the live offer (newer offer or other shipper). */
        STALE_COMMAND,
        /** Clear the expired offer and return to FINDING_SHIPPER. */
        EXPIRE
    }

    public static ExpiryDecision onExpire(DeliveryStatus status, String commandSession, String offeredSession,
                                          Long offeredShipperId, LocalDateTime offerExpiresAt,
                                          long timedOutShipperId, LocalDateTime expectedExpiresAt,
                                          LocalDateTime now) {
        if (status == DeliveryStatus.ASSIGNED) return ExpiryDecision.ASSIGNED;
        if (status == DeliveryStatus.CANCELLED || status == DeliveryStatus.SHIPPER_NOT_FOUND) {
            return ExpiryDecision.TERMINAL;
        }
        if (commandSession != null && !commandSession.isBlank()
                && offeredSession != null && !commandSession.equals(offeredSession)) {
            return ExpiryDecision.STALE_SESSION;
        }
        if (status == DeliveryStatus.FINDING_SHIPPER && offeredShipperId == null && offerExpiresAt == null) {
            return ExpiryDecision.ALREADY_RETIRED;
        }
        if (status != DeliveryStatus.WAIT_SHIPPER_CONFIRM
                || offeredShipperId == null || offeredShipperId != timedOutShipperId
                || !sameDeadline(expectedExpiresAt, offerExpiresAt)) {
            return ExpiryDecision.STALE_COMMAND;
        }
        if (offerExpiresAt.isAfter(now)) {
            throw new OfferDecisionRejected(INVALID_STATUS, "Cannot expire a shipper offer before its deadline");
        }
        return ExpiryDecision.EXPIRE;
    }

    /** Outcome reported on delivery.offer-retired. */
    public static String retirementOutcome(ExpiryDecision decision) {
        return switch (decision) {
            case ASSIGNED -> "ASSIGNED";
            case TERMINAL -> "TERMINAL";
            default -> "RETIRED";
        };
    }
}
