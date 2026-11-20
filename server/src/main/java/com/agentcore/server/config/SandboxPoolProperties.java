package com.agentcore.server.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Warm pool settings (ADR-0026). The pool only accelerates container startup;
 * an empty or broken pool always falls back to cold creation.
 */
@ConfigurationProperties(prefix = "app.sandbox.pool")
public record SandboxPoolProperties(
        Boolean enabled, Integer target, Integer max, Duration idleTtl, Integer replenishWorkers) {

    public SandboxPoolProperties {
        if (enabled == null) {
            enabled = Boolean.TRUE;
        }
        if (target == null) {
            target = 12;
        }
        if (max == null) {
            max = 16;
        }
        if (idleTtl == null || idleTtl.isNegative() || idleTtl.isZero()) {
            idleTtl = Duration.ofMinutes(10);
        }
        if (replenishWorkers == null || replenishWorkers <= 0) {
            replenishWorkers = 2;
        }
        if (target < 0) {
            target = 0;
        }
        if (max < 0) {
            max = 0;
        }
        if (target > max) {
            target = max;
        }
    }

    public static SandboxPoolProperties defaults() {
        return new SandboxPoolProperties(null, null, null, null, null);
    }
}
