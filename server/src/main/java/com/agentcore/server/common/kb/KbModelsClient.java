package com.agentcore.server.common.kb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * OpenAI-compatible {@code GET /models} lookup for the settings dialog. Many
 * self-hosted endpoints do not implement it, so callers treat any failure as
 * "manual model input" rather than a save blocker.
 */
@Component
public class KbModelsClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    public KbModelsClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<String> listModels(String baseUrl, String apiKey) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint(baseUrl)))
                .timeout(REQUEST_TIMEOUT)
                .header("accept", "application/json")
                .GET();
        if (apiKey != null && !apiKey.isBlank()) {
            request.header("authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(
                    StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new KbModelsException("model list endpoint is unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KbModelsException("model list request was interrupted", e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new KbModelsException(
                    "model list endpoint returned status " + response.statusCode(), null);
        }
        return parse(response.body());
    }

    private List<String> parse(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException e) {
            throw new KbModelsException("model list response is not valid JSON", e);
        }
        JsonNode items = root.has("data") ? root.path("data") : root.path("models");
        if (!items.isArray()) {
            throw new KbModelsException("model list response has no data array", null);
        }
        Set<String> models = new LinkedHashSet<>();
        for (JsonNode item : items) {
            String id = item.isTextual() ? item.asText("") : item.path("id").asText("");
            if (!id.isBlank()) {
                models.add(id);
            }
        }
        List<String> sorted = new ArrayList<>(models);
        sorted.sort(String::compareTo);
        return sorted;
    }

    private static String endpoint(String baseUrl) {
        String base = baseUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/models";
    }
}
