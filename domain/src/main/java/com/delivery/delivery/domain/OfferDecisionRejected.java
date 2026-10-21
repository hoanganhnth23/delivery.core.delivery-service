package com.delivery.delivery.domain;

/** A shipper offer decision that the delivery rules refuse; the adapter maps the kind to its HTTP error. */
public final class OfferDecisionRejected extends RuntimeException {

    public enum Kind { ACCESS_DENIED, INVALID_STATUS }

    private final Kind kind;

    public OfferDecisionRejected(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
