package com.agentcore.server.dto.kb;

/** Effective KB settings; secrets are never echoed, only their last four characters. */
public record KbSettingsView(Embedding embedding, Rerank rerank) {

    /** {@code source} is {@code user}, {@code system} or {@code none}. */
    public record Embedding(
            String source,
            String baseUrl,
            String apiKeyHint,
            String model,
            Integer dimension,
            String path) {}

    /** {@code source} is {@code user}, {@code system}, {@code none} or {@code disabled}. */
    public record Rerank(
            String source,
            boolean enabled,
            String baseUrl,
            String apiKeyHint,
            String model,
            String path) {}
}
