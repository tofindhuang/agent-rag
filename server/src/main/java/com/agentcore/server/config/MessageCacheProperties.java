package com.agentcore.server.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Redis recent-message cache tuning (ADR-0006). */
@ConfigurationProperties(prefix = "app.message-cache")
public record MessageCacheProperties(Duration ttl, int maxEntries) {

    public Duration ttl() {
        return ttl == null ? Duration.ofHours(24) : ttl;
    }

    public int maxEntries() {
        return maxEntries <= 0 ? 100 : maxEntries;
    }
}
