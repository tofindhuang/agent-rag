package com.agentcore.server.common.kb;

import com.agentcore.server.common.error.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * OpenAI-compatible {@code /embeddings} client. Batches are capped at 32 and
 * each request has a 15s deadline; status/transport failures are classified so
 * the worker knows whether to retry or fail the document.
 */
@Component
public class KbEmbeddingClient {

    public static final int MAX_BATCH_SIZE = 32;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final ObjectMapper objectMapper;
    private final KbUrlGuard guard;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    public KbEmbeddingClient(ObjectMapper objectMapper, KbUrlGuard guard) {
        this.objectMapper = objectMapper;
        this.guard = guard;
    }

    public List<float[]> embed(KbEmbeddingConfig config, List<String> texts) {
        List<float[]> result = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += MAX_BATCH_SIZE) {
            int end = Math.min(texts.size(), start + MAX_BATCH_SIZE);
            result.addAll(request(config, texts.subList(start, end)));
        }
        return result;
    }

    private List<float[]> request(KbEmbeddingConfig config, List<String> batch) {
        URI uri;
        try {
            uri = guard.validate(endpoint(config));
        } catch (ApiException e) {
            throw KbIngestException.permanent(
                    KbIngestException.EMBEDDING_REJECTED, e.getMessage(), e);
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", config.model());
        ArrayNode input = body.putArray("input");
        batch.forEach(input::add);

        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("content-type", "application/json")
                .header("accept", "application/json");
        if (config.apiKey() != null && !config.apiKey().isBlank()) {
            request.header("authorization", "Bearer " + config.apiKey());
        }

        HttpResponse<String> response;
        try {
            request.POST(HttpRequest.BodyPublishers.ofString(
                    objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(
                    StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            throw KbIngestException.permanent(
                    KbIngestException.EMBEDDING_RESPONSE_INVALID,
                    "cannot serialize embedding request",
                    e);
        } catch (IOException e) {
            throw KbIngestException.retryable(
                    KbIngestException.EMBEDDING_UNAVAILABLE,
                    "embedding endpoint is unreachable: " + e.getMessage(),
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw KbIngestException.retryable(
                    KbIngestException.EMBEDDING_UNAVAILABLE,
                    "embedding request was interrupted",
                    e);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            boolean retryable = status == 408 || status == 429 || status >= 500;
            String code = retryable
                    ? KbIngestException.EMBEDDING_UNAVAILABLE
                    : KbIngestException.EMBEDDING_REJECTED;
            String message = "embedding endpoint returned status " + status;
            throw retryable
                    ? KbIngestException.retryable(code, message, null)
                    : KbIngestException.permanent(code, message);
        }

        return parse(config, response.body(), batch.size());
    }

    private List<float[]> parse(KbEmbeddingConfig config, String body, int expected) {
        JsonNode data;
        try {
            data = objectMapper.readTree(body).path("data");
        } catch (IOException e) {
            throw KbIngestException.permanent(
                    KbIngestException.EMBEDDING_RESPONSE_INVALID,
                    "embedding response is not valid JSON",
                    e);
        }
        if (!data.isArray() || data.size() != expected) {
            throw KbIngestException.permanent(
                    KbIngestException.EMBEDDING_RESPONSE_INVALID,
                    "embedding response did not contain one vector per input");
        }

        JsonNode[] ordered = new JsonNode[expected];
        for (int i = 0; i < data.size(); i++) {
            JsonNode item = data.get(i);
            int index = item.has("index") ? item.path("index").asInt(i) : i;
            if (index < 0 || index >= expected || ordered[index] != null) {
                throw KbIngestException.permanent(
                        KbIngestException.EMBEDDING_RESPONSE_INVALID,
                        "embedding response has an invalid index");
            }
            ordered[index] = item.path("embedding");
        }

        List<float[]> vectors = new ArrayList<>(expected);
        for (JsonNode embedding : ordered) {
            if (embedding == null || !embedding.isArray()
                    || embedding.size() != config.dimension()) {
                throw KbIngestException.permanent(
                        KbIngestException.EMBEDDING_DIMENSION_MISMATCH,
                        "embedding dimension does not match the configured "
                                + config.dimension());
            }
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            vectors.add(vector);
        }
        return vectors;
    }

    private static String endpoint(KbEmbeddingConfig config) {
        String base = config.baseUrl();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String path = config.path() == null || config.path().isBlank()
                ? "/embeddings"
                : config.path();
        return base + (path.startsWith("/") ? path : "/" + path);
    }
}
