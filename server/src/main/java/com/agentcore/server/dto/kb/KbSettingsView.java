package com.agentcore.server.dto.kb;

/** Effective KB settings; secrets are never echoed, only their last four characters. */
public record KbSettingsView(Embedding embedding, Rerank rerank, Threshold threshold) {

    /**
     * Refusal thresholds (ADR-0029); {@code null} means "do not refuse". Each
     * value applies to its own score scale.
     */
    public record Threshold(Double rerankMinScore, Double vectorMinScore) {}

    /** {@code source} is {@code user}, {@code system} or {@code none}. */
    public record Embedding(
            String source,
            String baseUrl,
            String apiKeyHint,
            String model,
            Integer dimension,
            int batchSize,
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
