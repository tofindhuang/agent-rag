package com.agentcore.server.dto.admin;

/** Token and cost totals; cost covers priced runs only. */
public record UsageAmount(
        long tokensIn,
        long tokensOut,
        long cacheReadTokens,
        long cacheWriteTokens,
        double cost,
        int unpricedRuns) {}
