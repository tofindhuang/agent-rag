package com.agentcore.server.common.quota;

import java.time.Instant;

/**
 * One user's monthly token budget (ADR-0032). {@code limitTokens} and
 * {@code remainingTokens} are null when no quota is configured; {@code used}
 * is always reported.
 */
public record QuotaStatus(
        boolean enforced,
        Long limitTokens,
        long usedTokens,
        Long remainingTokens,
        Instant resetsAt) {}
