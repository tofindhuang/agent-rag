package com.agentcore.server.dto.models;

public record ProbeRequest(String api, String baseUrl, String apiKey, Long providerId) {}
