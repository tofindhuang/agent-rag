package com.agentcore.server.service;

/**
 * Resolved model for a session, including the config to push to the gateway.
 *
 * <p>System sessions carry no binding; user sessions carry the provider
 * details needed to build one isolated runtime. Use the factories instead of
 * the canonical constructor.
 */
public record SessionModelChoice(Long providerModelId, String label, String source, Binding binding) {

    /** Provider details pushed to the gateway; null for system sessions. */
    public record Binding(
            String providerName,
            String api,
            String baseUrl,
            String apiKey,
            String modelId,
            String displayName,
            Integer contextWindow,
            boolean reasoning,
            boolean vision) {}

    public static SessionModelChoice system(String label) {
        return new SessionModelChoice(null, label, "system", null);
    }

    public static SessionModelChoice user(long providerModelId, String label, Binding binding) {
        return new SessionModelChoice(providerModelId, label, "user", binding);
    }

    public boolean usesUserProvider() {
        return binding != null;
    }
}
