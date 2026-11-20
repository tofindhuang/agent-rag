package com.agentcore.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Monthly token quota (ADR-0032). {@code monthlyTokens <= 0} means "no quota";
 * per-user overrides land with the admin write slice.
 */
@ConfigurationProperties(prefix = "app.quota")
public record QuotaProperties(Long monthlyTokens) {

    public QuotaProperties {
        if (monthlyTokens == null) {
            monthlyTokens = 0L;
        }
    }

    public boolean enforced() {
        return monthlyTokens > 0;
    }

    public static QuotaProperties defaults() {
        return new QuotaProperties(0L);
    }
}
