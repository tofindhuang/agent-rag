package com.agentcore.server.dto.kb;

/**
 * Rerank-only update: {@code enabled} alone toggles the switch (fields are
 * preserved); a base URL replaces the stored rerank service.
 */
public record KbRerankUpdateRequest(
        Boolean enabled, String baseUrl, String apiKey, String model, String path) {}
