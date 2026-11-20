package com.agentcore.server.dto.models;

public record ProviderModelView(
        long id,
        String modelId,
        String displayName,
        Integer contextWindow,
        boolean reasoning,
        boolean vision) {}
