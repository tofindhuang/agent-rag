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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * OpenAI-compatible {@code /rerank} client (BGE / Jina / Cohere shape). The
 * settings probe and retrieval share it so both classify failures the same
 * way: 408/429/5xx and transport errors are retryable, other 4xx and malformed
 * payloads are permanent.
 */
@Component
public class KbRerankClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final ObjectMapper objectMapper;
    private final KbUrlGuard guard;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    public KbRerankClient(ObjectMapper objectMapper, KbUrlGuard guard) {
        this.objectMapper = objectMapper;
        this.guard = guard;
    }

    public KbRerankResult rerank(
            KbRerankConfig config, String query, List<String> documents, int topN) {
        return rerank(config, query, documents, topN, REQUEST_TIMEOUT);
    }

    /** Retrieval passes the remaining 20s budget so one call cannot overrun it. */
    public KbRerankResult rerank(
            KbRerankConfig config,
            String query,
            List<String> documents,
            int topN,
            Duration timeout) {
        if (documents == null || documents.isEmpty()) {
            return new KbRerankResult(List.of(), 0);
        }
        URI uri;
        try {
            uri = guard.validate(endpoint(config));
        } catch (ApiException e) {
            throw KbRerankException.permanent(
                    KbRerankException.RERANK_REJECTED, e.getMessage(), e);
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", config.model());
        body.put("query", query);
        ArrayNode inputs = body.putArray("documents");
        documents.forEach(inputs::add);
        if (topN > 0) {
            body.put("top_n", Math.min(topN, documents.size()));
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("content-type", "application/json")
                .header("accept", "application/json");
        if (config.apiKey() != null && !config.apiKey().isBlank()) {
            request.header("authorization", "Bearer " + config.apiKey());
        }

        long started = System.nanoTime();
        HttpResponse<String> response;
        try {
            request.POST(HttpRequest.BodyPublishers.ofString(
                    objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(
                    StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            throw KbRerankException.permanent(
                    KbRerankException.RERANK_RESPONSE_INVALID,
                    "cannot serialize rerank request",
                    e);
        } catch (IOException e) {
            throw KbRerankException.retryable(
                    KbRerankException.RERANK_UNAVAILABLE,
                    "rerank endpoint is unreachable: " + e.getMessage(),
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw KbRerankException.retryable(
                    KbRerankException.RERANK_UNAVAILABLE, "rerank request was interrupted", e);
        }
        long latencyMs = (System.nanoTime() - started) / 1_000_000L;

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            boolean retryable = status == 408 || status == 429 || status >= 500;
            String message = "rerank endpoint returned status " + status;
            throw retryable
                    ? KbRerankException.retryable(
                            KbRerankException.RERANK_UNAVAILABLE, message, null)
                    : KbRerankException.permanent(
                            KbRerankException.RERANK_REJECTED, message);
        }
        return new KbRerankResult(parse(response.body(), documents.size()), latencyMs);
    }

    private List<KbRerankHit> parse(String body, int documentCount) {
        JsonNode results;
        try {
            results = objectMapper.readTree(body).path("results");
        } catch (IOException e) {
            throw KbRerankException.permanent(
                    KbRerankException.RERANK_RESPONSE_INVALID,
                    "rerank response is not valid JSON",
                    e);
        }
        if (!results.isArray()) {
            throw KbRerankException.permanent(
                    KbRerankException.RERANK_RESPONSE_INVALID,
                    "rerank response did not contain a results array");
        }

        List<KbRerankHit> hits = new ArrayList<>(results.size());
        Set<Integer> seen = new HashSet<>();
        for (JsonNode node : results) {
            int index = node.path("index").asInt(-1);
            JsonNode scoreNode = node.has("relevance_score")
                    ? node.path("relevance_score")
                    : node.path("score");
            if (index < 0 || index >= documentCount || !seen.add(index)
                    || !scoreNode.isNumber()) {
                throw KbRerankException.permanent(
                        KbRerankException.RERANK_RESPONSE_INVALID,
                        "rerank response contained an invalid result");
            }
            hits.add(new KbRerankHit(index, scoreNode.asDouble()));
        }
        if (hits.isEmpty()) {
            throw KbRerankException.permanent(
                    KbRerankException.RERANK_RESPONSE_INVALID,
                    "rerank response was empty");
        }
        return hits;
    }

    private static String endpoint(KbRerankConfig config) {
        String base = config.baseUrl();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String path = config.path() == null || config.path().isBlank()
                ? "/rerank"
                : config.path();
        return base + (path.startsWith("/") ? path : "/" + path);
    }
}
