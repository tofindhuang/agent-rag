package com.agentcore.server.dto.usage;

import java.time.Instant;

/**
 * Monthly token budget of the current user (ADR-0032). With no quota
 * configured only {@code usedTokens} is present; {@code remainingTokens} never
 * goes negative.
 */
public record QuotaView(
        boolean enforced,
        Long limitTokens,
        long usedTokens,
        Long remainingTokens,
        Instant resetsAt) {}
