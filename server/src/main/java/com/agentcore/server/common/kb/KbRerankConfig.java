package com.agentcore.server.common.kb;

/** Effective rerank endpoint for one retrieval run (user settings or deployment fallback). */
public record KbRerankConfig(String baseUrl, String apiKey, String model, String path) {}
