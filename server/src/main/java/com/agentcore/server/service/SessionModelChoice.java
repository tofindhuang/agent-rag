package com.agentcore.server.service;

/** Resolved model for a session, including the config to push to the gateway. */
public record SessionModelChoice(
        Long providerModelId,
        String label,
        String source,
        String providerName,
        String api,
        String baseUrl,
        String apiKey,
        String modelId,
        String displayName,
        Integer contextWindow,
        boolean reasoning,
        boolean vision) {

    public boolean usesUserProvider() {
        return "user".equals(source);
    }
}
