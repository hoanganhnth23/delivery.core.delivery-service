package com.delivery.delivery_service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class BusinessMetrics {
    private final MeterRegistry registry;
    public BusinessMetrics(MeterRegistry registry) { this.registry = registry; }
    public void record(String event) {
        Counter.builder("delivery.business.events").tag("event", event).tag("outcome", "success")
                .register(registry).increment();
    }
    public void kafka(String event) {
        Counter.builder("delivery.kafka.events").tag("event", event).register(registry).increment();
    }
    /** Records use of a bounded, temporary legacy ownership compatibility path. */
    public void identityLegacyFallback(String surface) {
        Counter.builder("delivery.identity.legacy.fallback")
                .tag("service", "delivery").tag("surface", surface).register(registry).increment();
    }
}
