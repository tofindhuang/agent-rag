package com.agentcore.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway (pi engine) connection settings. The gateway is the only path to
 * the LLM; this service never calls a model directly.
 */
@ConfigurationProperties(prefix = "app.gateway")
public record GatewayProperties(String baseUrl, String apiToken) {

    public String baseUrl() {
        return baseUrl == null || baseUrl.isBlank() ? "http://localhost:3001" : baseUrl;
    }

    public boolean hasToken() {
        return apiToken != null && !apiToken.isBlank();
    }
}
