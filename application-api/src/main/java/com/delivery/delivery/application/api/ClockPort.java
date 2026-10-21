package com.delivery.delivery.application.api;

import java.time.Instant;

/** Supplies application time without coupling the core to a runtime clock. */
@FunctionalInterface
public interface ClockPort {
    Instant now();
}
