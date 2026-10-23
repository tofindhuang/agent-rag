package com.agentcore.server.common.messaging;

/**
 * Ingestion retry ladder (FR-2): a failed transient attempt is published to the
 * matching TTL queue; when the TTL expires RabbitMQ dead-letters it back to the
 * ingest queue. Attempt 4 fails permanently and goes to the DLQ.
 */
public enum KbRetryTier {
    FIVE_SECONDS("5s", 5_000L),
    THIRTY_SECONDS("30s", 30_000L),
    TWO_MINUTES("2m", 120_000L);

    private final String label;
    private final long ttlMillis;

    KbRetryTier(String label, long ttlMillis) {
        this.label = label;
        this.ttlMillis = ttlMillis;
    }

    public String label() {
        return label;
    }

    public long ttlMillis() {
        return ttlMillis;
    }

    /**
     * Retry tier for a 1-based failure count (1 → 5s, 2 → 30s, 3 → 2m).
     * Returns {@code null} when the ladder is exhausted.
     */
    public static KbRetryTier forFailure(int failureCount) {
        return switch (failureCount) {
            case 1 -> FIVE_SECONDS;
            case 2 -> THIRTY_SECONDS;
            case 3 -> TWO_MINUTES;
            default -> null;
        };
    }
}
