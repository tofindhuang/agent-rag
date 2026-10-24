package com.agentcore.server.dto.kb;

/**
 * PUT body: the whole settings object is replaced. A {@code null} API key
 * keeps the stored cipher (so a form can save without re-entering it), while
 * an empty string clears it.
 */
public record KbSettingsUpdateRequest(Embedding embedding, Rerank rerank) {

    public record Embedding(
            String baseUrl, String apiKey, String model, Integer dimension, String path) {}

    public record Rerank(
            Boolean enabled, String baseUrl, String apiKey, String model, String path) {}
}
