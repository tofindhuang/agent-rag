package com.agentcore.server.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-user and per-IP limits (FR-6). Token bucket (ADR-0030): each limit is the
 * bucket capacity refilling at {@code limit / window}.
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        Boolean enabled,
        Integer userRpm,
        Integer userTpm,
        Integer authIpRpm,
        Duration window,
        List<String> trustedProxies) {

    public RateLimitProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (userRpm == null || userRpm <= 0) {
            userRpm = 120;
        }
        if (userTpm == null || userTpm <= 0) {
            userTpm = 100_000;
        }
        if (authIpRpm == null || authIpRpm <= 0) {
            authIpRpm = 10;
        }
        if (window == null || window.isNegative() || window.isZero()) {
            window = Duration.ofSeconds(60);
        }
        if (trustedProxies == null) {
            trustedProxies = List.of();
        } else {
            trustedProxies = List.copyOf(trustedProxies);
        }
    }

    public static RateLimitProperties defaults() {
        return new RateLimitProperties(null, null, null, null, null, null);
    }
}
