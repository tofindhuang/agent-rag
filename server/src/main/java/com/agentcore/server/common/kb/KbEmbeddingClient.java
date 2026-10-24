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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * OpenAI-compatible {@code /embeddings} client. Batches are capped at 32 and
 * each request has a 15s deadline; status/transport failures are classified so
 * the worker knows whether to retry or fail the document.
 */
@Component
public class KbEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(KbEmbeddingClient.class);

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
        return embed(config, texts, REQUEST_TIMEOUT);
    }

    /** Retrieval passes the remaining 20s budget so one call cannot overrun it. */
    public List<float[]> embed(
            KbEmbeddingConfig config, List<String> texts, Duration timeout) {
        List<float[]> result = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += MAX_BATCH_SIZE) {
            int end = Math.min(texts.size(), start + MAX_BATCH_SIZE);
            List<String> batch = texts.subList(start, end);
            try {
                result.addAll(request(config, batch, true, timeout));
            } catch (BatchIndexFallbackException e) {
                // Never guess the mapping: if a provider's batch response has
                // no trustworthy index, re-embed this batch one input at a
                // time. Slow only for non-conforming providers.
                log.warn("embedding batch response has no usable index mapping;"
                        + " retrying {} inputs individually", batch.size());
                for (String text : batch) {
                    result.addAll(request(config, List.of(text), true, timeout));
                }
            }
        }
        return result;
    }

    /**
     * One-vector sanity call for the settings probe. The returned dimension is
     * the endpoint's actual size, so the caller can reject a wrong claim even
     * when the stored configuration is not the one being probed.
     */
    public KbEmbeddingProbe probe(KbEmbeddingConfig config) {
        long started = System.nanoTime();
        List<float[]> vectors = request(
                config, List.of("knowledge base dimension probe"), false, REQUEST_TIMEOUT);
        long latencyMs = (System.nanoTime() - started) / 1_000_000L;
        if (vectors.isEmpty() || vectors.get(0).length == 0) {
            throw KbIngestException.permanent(
                    KbIngestException.EMBEDDING_RESPONSE_INVALID,
                    "embedding endpoint returned an empty vector");
        }
        return new KbEmbeddingProbe(vectors.get(0).length, latencyMs);
    }

    private List<float[]> request(
            KbEmbeddingConfig config,
            List<String> batch,
            boolean assertDimension,
            Duration timeout) {
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
                .timeout(timeout)
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

        return parse(config, response.body(), batch.size(), assertDimension);
    }

    private List<float[]> parse(
            KbEmbeddingConfig config, String body, int expected, boolean assertDimension) {
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
            if (expected > 1) {
                throw new BatchIndexFallbackException();
            }
            throw KbIngestException.permanent(
                    KbIngestException.EMBEDDING_RESPONSE_INVALID,
                    "embedding response did not contain one vector per input");
        }

        JsonNode[] ordered = new JsonNode[expected];
        int[] indexes = new int[expected];
        boolean hasAllIndexes = true;
        for (int i = 0; i < data.size(); i++) {
            JsonNode item = data.get(i);
            JsonNode indexNode = item.get("index");
            if (indexNode == null || !indexNode.isNumber() || !indexNode.canConvertToInt()) {
                hasAllIndexes = false;
                break;
            }
            int index = indexNode.asInt();
            if (index < 0 || index >= expected + 1 || ordered[index % expected] != null) {
                hasAllIndexes = false;
                break;
            }
            indexes[i] = index;
        }
        if (!hasAllIndexes) {
            if (expected == 1) {
                ordered[0] = data.get(0).path("embedding");
            } else {
                throw new BatchIndexFallbackException();
            }
        } else {
            boolean zeroBased = true;
            boolean oneBased = true;
            boolean[] seen = new boolean[expected];
            for (int index : indexes) {
                if (index < 0 || index >= expected) {
                    zeroBased = false;
                } else {
                    if (seen[index]) {
                        zeroBased = false;
                    }
                    seen[index] = true;
                }
                if (index < 1 || index > expected) {
                    oneBased = false;
                }
            }
            if (zeroBased) {
                for (int i = 0; i < expected; i++) {
                    ordered[i] = data.get(indexOf(indexes, i)).path("embedding");
                }
            } else if (oneBased && hasUniqueOneBased(indexes, expected)) {
                for (int i = 0; i < expected; i++) {
                    ordered[i] = data.get(indexOf(indexes, i + 1)).path("embedding");
                }
            } else {
                throw new BatchIndexFallbackException();
            }
        }

        List<float[]> vectors = new ArrayList<>(expected);
        int dimension = assertDimension ? config.dimension() : -1;
        for (JsonNode embedding : ordered) {
            if (embedding == null || !embedding.isArray() || embedding.size() == 0) {
                throw KbIngestException.permanent(
                        KbIngestException.EMBEDDING_RESPONSE_INVALID,
                        "embedding response contained an empty vector");
            }
            if (dimension < 0) {
                dimension = embedding.size();
            }
            if (embedding.size() != dimension) {
                throw KbIngestException.permanent(
                        assertDimension
                                ? KbIngestException.EMBEDDING_DIMENSION_MISMATCH
                                : KbIngestException.EMBEDDING_RESPONSE_INVALID,
                        assertDimension
                                ? "embedding dimension does not match the configured "
                                        + config.dimension()
                                : "embedding response vectors have inconsistent dimensions");
            }
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            vectors.add(vector);
        }
        return vectors;
    }

    private static boolean hasUniqueOneBased(int[] indexes, int expected) {
        boolean[] seen = new boolean[expected + 1];
        for (int index : indexes) {
            if (index < 1 || index > expected || seen[index]) {
                return false;
            }
            seen[index] = true;
        }
        return true;
    }

    private static int indexOf(int[] indexes, int value) {
        for (int i = 0; i < indexes.length; i++) {
            if (indexes[i] == value) {
                return i;
            }
        }
        throw new BatchIndexFallbackException();
    }

    private static final class BatchIndexFallbackException extends RuntimeException {}

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
