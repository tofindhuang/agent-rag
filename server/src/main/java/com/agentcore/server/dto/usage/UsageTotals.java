package com.agentcore.server.dto.usage;

/** Totals for today or the selected window. Cost covers priced models only. */
public record UsageTotals(
        long tokensIn,
        long tokensOut,
        long cacheReadTokens,
        long cacheWriteTokens,
        double cost,
        int unpricedRuns,
        long executions,
        long searches) {}
