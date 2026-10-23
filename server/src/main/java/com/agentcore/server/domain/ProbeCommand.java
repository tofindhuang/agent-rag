package com.agentcore.server.domain;

public record ProbeCommand(String api, String baseUrl, String apiKey, Long providerId) {}
