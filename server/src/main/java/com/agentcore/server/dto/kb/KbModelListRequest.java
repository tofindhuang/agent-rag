package com.agentcore.server.dto.kb;

/** Candidate endpoint for the settings model picker; the key is never echoed. */
public record KbModelListRequest(String type, String baseUrl, String apiKey) {}
