package com.agentcore.server.dto.admin;

import java.time.Instant;

/** Session metadata without its title (admin privacy boundary). */
public record AdminUserSession(
        String sessionId,
        Instant createdAt,
        Instant lastActiveAt,
        String modelId,
        long tokensIn,
        long tokensOut,
        long cacheReadTokens,
        long cacheWriteTokens,
        double cost,
        long executions) {}
