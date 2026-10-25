package com.agentcore.server.dto.usage;

/** One deployment-timezone day, already merged across models. */
public record UsageDaily(
        String date,
        long tokensIn,
        long tokensOut,
        long cacheReadTokens,
        long cacheWriteTokens,
        double cost,
        int unpricedRuns,
        long executions,
        long searches) {}
