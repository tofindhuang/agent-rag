package com.agentcore.server.dto.admin;

/** One deployment-timezone day for the admin charts and table. */
public record AdminDaily(
        String date,
        long tokensIn,
        long tokensOut,
        long cacheReadTokens,
        long cacheWriteTokens,
        double cost,
        int unpricedRuns,
        long executions,
        long compactions,
        Double hitRate) {}
