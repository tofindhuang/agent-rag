package com.agentcore.server.common.kb;

/** Effective embedding endpoint for one ingestion run (user settings or deployment fallback). */
public record KbEmbeddingConfig(
        String baseUrl, String apiKey, String model, String path, int dimension) {}
