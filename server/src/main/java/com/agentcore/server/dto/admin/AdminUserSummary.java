package com.agentcore.server.dto.admin;

import java.time.Instant;

/** One row of the admin user list; no session titles or content. */
public record AdminUserSummary(
        long userId,
        String email,
        Instant createdAt,
        Instant lastActiveAt,
        long sessions,
        long tokensIn,
        long tokensOut,
        long cacheReadTokens,
        long cacheWriteTokens,
        double cost,
        int unpricedRuns,
        long executions,
        long searches) {}
