package com.agentcore.server.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway (pi engine) connection settings. The gateway is the only path to
 * the LLM; this service never calls a model directly.
 *
 * <p>{@code urls} is a static seed list for multi-instance routing (ADR-0024);
 * {@code baseUrl} remains the single-instance fallback.
 */
@ConfigurationProperties(prefix = "app.gateway")
public record GatewayProperties(String baseUrl, String apiToken, String urls) {

    public String baseUrl() {
        return baseUrl == null || baseUrl.isBlank() ? "http://localhost:3001" : normalize(baseUrl);
    }

    public boolean hasToken() {
        return apiToken != null && !apiToken.isBlank();
    }

    /** Static fallback addresses when the Redis registry is empty or unavailable. */
    public List<String> seedUrls() {
        List<String> seeds = urls == null
                ? List.of()
                : Arrays.stream(urls.split(","))
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .map(GatewayProperties::normalize)
                        .toList();
        return seeds.isEmpty() ? List.of(baseUrl()) : seeds;
    }

    private static String normalize(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
