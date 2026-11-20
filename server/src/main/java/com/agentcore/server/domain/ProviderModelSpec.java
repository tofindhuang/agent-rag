package com.agentcore.server.domain;

/** User input for one enabled model. */
public record ProviderModelSpec(
        String modelId,
        String displayName,
        Integer contextWindow,
        boolean reasoning,
        boolean vision) {}
