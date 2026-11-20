package com.agentcore.server.dto.kb;

/** Rerank-only probe for the settings dialog; endpoint/key are not persisted. */
public record KbRerankProbeRequest(String baseUrl, String apiKey, String model, String path) {}
