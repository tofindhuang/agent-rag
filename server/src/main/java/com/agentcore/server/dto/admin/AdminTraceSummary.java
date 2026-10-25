package com.agentcore.server.dto.admin;

import java.time.Instant;

/** Identity, model and token summary for one traced run. */
public record AdminTraceSummary(
        String requestId,
        long userId,
        String email,
        String sessionId,
        String turnId,
        String runId,
        String modelId,
        String status,
        long tokensIn,
        long tokensOut,
        long cacheReadTokens,
        long cacheWriteTokens,
        Double cost,
        Instant startedAt,
        Instant settledAt) {}
