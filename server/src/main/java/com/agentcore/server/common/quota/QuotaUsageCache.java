package com.agentcore.server.common.quota;

import java.time.Duration;

/** Tiny cache port for the usage display; enforcement always reads MySQL. */
public interface QuotaUsageCache {

    /** @return the cached value, or {@code null} when absent/unreadable */
    String get(String key);

    void put(String key, String value, Duration ttl);
}
