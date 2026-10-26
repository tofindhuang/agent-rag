package com.agentcore.server.common.sandbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Pool metrics (ADR-0026). Labels never include userId or execId, so cardinality
 * stays bounded regardless of traffic.
 */
@Component
public class SandboxPoolMetrics {

    private final MeterRegistry registry;
    private final AtomicInteger idle = new AtomicInteger();
    private final AtomicInteger creating = new AtomicInteger();

    public SandboxPoolMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("sandbox_pool_idle", idle, AtomicInteger::doubleValue).register(registry);
        Gauge.builder("sandbox_pool_creating", creating, AtomicInteger::doubleValue)
                .register(registry);
    }

    public void idle(int count) {
        idle.set(count);
    }

    public void creating(int count) {
        creating.set(count);
    }

    public void checkout(String result) {
        counter("sandbox_pool_checkout_total", result);
    }

    public void coldFallback() {
        Counter.builder("sandbox_pool_cold_fallback_total").register(registry).increment();
    }

    public void replenishFailure() {
        Counter.builder("sandbox_pool_replenish_failures_total").register(registry).increment();
    }

    public void containerLifecycle(long durationMs) {
        DistributionSummary.builder("sandbox_container_lifecycle_ms")
                .register(registry)
                .record(durationMs);
    }

    private void counter(String name, String result) {
        Counter.builder(name).tag("result", result).register(registry).increment();
    }
}
