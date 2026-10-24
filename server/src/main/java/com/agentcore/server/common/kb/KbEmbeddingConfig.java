package com.agentcore.server.common.kb;

/** Effective embedding endpoint for one ingestion run (user settings or deployment fallback). */
public record KbEmbeddingConfig(
        String baseUrl, String apiKey, String model, String path, int dimension, int batchSize) {

    public KbEmbeddingConfig(String baseUrl, String apiKey, String model, String path, int dimension) {
        this(baseUrl, apiKey, model, path, dimension, 32);
    }
}
