package com.agentcore.server.dto.usage;

import java.time.Instant;

/** A user's own session ranked by tokens in the selected window. */
public record UsageTopSession(
        String sessionId,
        String title,
        Instant lastActiveAt,
        long tokensIn,
        long tokensOut,
        long cacheReadTokens,
        long cacheWriteTokens,
        double cost,
        long executions) {}
