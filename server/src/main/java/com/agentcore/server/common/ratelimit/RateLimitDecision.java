package com.agentcore.server.common.ratelimit;

/** Result of one limit check; retryAfter is meaningful when rejected. */
public record RateLimitDecision(boolean allowed, long retryAfterSeconds) {

    public static RateLimitDecision permit() {
        return new RateLimitDecision(true, 0);
    }
}
