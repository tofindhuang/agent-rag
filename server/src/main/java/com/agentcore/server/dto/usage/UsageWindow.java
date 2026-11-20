package com.agentcore.server.dto.usage;

/** Totals for the selected window, including its length in days. */
public record UsageWindow(
        int days,
        long tokensIn,
        long tokensOut,
        long cacheReadTokens,
        long cacheWriteTokens,
        double cost,
        int unpricedRuns,
        long executions,
        long searches) {}
