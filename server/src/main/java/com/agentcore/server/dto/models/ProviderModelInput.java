package com.agentcore.server.dto.models;

public record ProviderModelInput(
        String modelId,
        String displayName,
        Integer contextWindow,
        Boolean reasoning,
        Boolean vision) {}
