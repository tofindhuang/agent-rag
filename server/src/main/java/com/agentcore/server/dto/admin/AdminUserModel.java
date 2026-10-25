package com.agentcore.server.dto.admin;

/** Token and cost split by model for one user. */
public record AdminUserModel(
        String modelId,
        long tokensIn,
        long tokensOut,
        long cacheReadTokens,
        long cacheWriteTokens,
        double cost) {}
