package com.agentcore.server.common.vector;

import com.agentcore.server.common.kb.KbDimensionMismatchException;
import com.agentcore.server.common.kb.KbStoreException;
import com.agentcore.server.config.KbQdrantProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Qdrant REST boundary. Collections are per user and created lazily; the
 * vector size is locked so an embedding-dimension change cannot silently
 * corrupt an existing index.
 *
 * <p>Uses the REST API directly (same JSON/HTTP shape as the spike) instead of
 * the gRPC client: it keeps the dependency surface small and makes failures
 * easy to classify as retryable vs permanent.
 */
@Component
public class QdrantVectorStore implements VectorStore {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final KbQdrantProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
    private final Map<String, Object> collectionLocks = new ConcurrentHashMap<>();

    public QdrantVectorStore(KbQdrantProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String collectionName(long userId) {
        return properties.collectionPrefix() + "_u" + userId;
    }

    @Override
    public List<VectorCollection> collections() {
        QdrantResponse response = send("GET", "/collections", null);
        requireSuccess(response, "list collections");
        JsonNode list = response.body().path("result").path("collections");
        List<VectorCollection> result = new ArrayList<>();
        if (!list.isArray()) {
            return result;
        }
        for (JsonNode node : list) {
            String name = node.path("name").asText("");
            if (!name.startsWith(properties.collectionPrefix() + "_u")) {
                continue;
            }
            collection(name).ifPresent(result::add);
        }
        return result;
    }

    @Override
    public void ensureCollection(long userId, int dimension) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be positive");
        }
        String name = collectionName(userId);
        Optional<VectorCollection> existing = collection(name);
        if (existing.isPresent()) {
            requireDimension(name, existing.get().dimension(), dimension);
            createPayloadIndexes(name);
            return;
        }

        synchronized (lockFor(name)) {
            existing = collection(name);
            if (existing.isPresent()) {
                requireDimension(name, existing.get().dimension(), dimension);
                createPayloadIndexes(name);
                return;
            }
            try {
                createCollection(name, dimension);
            } catch (KbStoreException e) {
                // A concurrent creator won the race; verify its dimension instead.
                Optional<VectorCollection> raced = collection(name);
                if (raced.isPresent()) {
                    requireDimension(name, raced.get().dimension(), dimension);
                    createPayloadIndexes(name);
                    return;
                }
                throw e;
            }
            createPayloadIndexes(name);
        }
    }

    @Override
    public void recreateCollection(long userId, int dimension) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be positive");
        }
        String name = collectionName(userId);
        synchronized (lockFor(name)) {
            QdrantResponse response = send("DELETE", "/collections/" + encode(name), null);
            if (response.status() != 404) {
                requireSuccess(response, "delete collection " + name);
            }
            createCollection(name, dimension);
            createPayloadIndexes(name);
        }
    }

    @Override
    public void upsert(long userId, int dimension, List<VectorPoint> points) {
        if (points == null || points.isEmpty()) {
            return;
        }
        ensureCollection(userId, dimension);
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode array = body.putArray("points");
        for (VectorPoint point : points) {
            if (point.payload().userId() != userId) {
                throw KbStoreException.permanent(
                        "point payload user_id does not match the target collection", null);
            }
            if (point.vector().length != dimension) {
                throw new KbDimensionMismatchException(
                        "point vector has dimension " + point.vector().length
                                + " but collection expects " + dimension);
            }
            ObjectNode node = array.addObject();
            node.put("id", point.id());
            ArrayNode vector = node.putArray("vector");
            for (float value : point.vector()) {
                vector.add(value);
            }
            node.set("payload", objectMapper.valueToTree(point.payload().toMap()));
        }
        QdrantResponse response = send(
                "PUT", "/collections/" + encode(collectionName(userId)) + "/points?wait=true", body);
        requireSuccess(response, "upsert points");
    }

    @Override
    public void deleteByDocument(long userId, String documentId) {
        String name = collectionName(userId);
        if (collection(name).isEmpty()) {
            return;
        }
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode must = body.putObject("filter").putArray("must");
        must.addObject().put("key", "user_id").putObject("match").put("value", userId);
        must.addObject().put("key", "document_id").putObject("match").put("value", documentId);
        QdrantResponse response = send(
                "POST", "/collections/" + encode(name) + "/points/delete?wait=true", body);
        if (response.status() == 404) {
            return;
        }
        requireSuccess(response, "delete points");
    }

    @Override
    public List<VectorHit> search(long userId, float[] query, int limit, Duration timeout) {
        if (limit <= 0 || query == null || query.length == 0) {
            return List.of();
        }
        String name = collectionName(userId);
        Optional<VectorCollection> collection = collection(name);
        if (collection.isEmpty()) {
            return List.of();
        }
        if (collection.get().dimension() != query.length) {
            throw new KbDimensionMismatchException(
                    "query vector has dimension " + query.length
                            + " but collection " + name + " expects "
                            + collection.get().dimension());
        }

        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode vector = body.putArray("vector");
        for (float value : query) {
            vector.add(value);
        }
        body.put("limit", limit);
        body.put("with_payload", true);
        body.put("with_vector", false);
        body.putObject("filter").putArray("must")
                .addObject().put("key", "user_id").putObject("match").put("value", userId);

        QdrantResponse response = send(
                "POST", "/collections/" + encode(name) + "/points/search", body, timeout);
        if (response.status() == 404) {
            return List.of();
        }
        requireSuccess(response, "search points");

        List<VectorHit> hits = new ArrayList<>();
        JsonNode result = response.body().path("result");
        if (!result.isArray()) {
            return hits;
        }
        for (JsonNode node : result) {
            JsonNode payload = node.path("payload");
            if (!payload.isObject()) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> values = objectMapper.convertValue(payload, Map.class);
            hits.add(new VectorHit(
                    node.path("id").asText(),
                    node.path("score").asDouble(),
                    VectorPayload.fromMap(values)));
        }
        return hits;
    }

    private Optional<VectorCollection> collection(String name) {
        QdrantResponse response = send("GET", "/collections/" + encode(name), null);
        if (response.status() == 404) {
            return Optional.empty();
        }
        requireSuccess(response, "get collection " + name);
        JsonNode vectors = response.body()
                .path("result").path("config").path("params").path("vectors");
        int dimension = vectorSize(name, vectors);
        return Optional.of(new VectorCollection(name, dimension));
    }

    private static int vectorSize(String name, JsonNode vectors) {
        if (vectors.has("size")) {
            return vectors.path("size").asInt();
        }
        // Named-vector configs are an object keyed by name; accept the common
        // single-vector case and reject ambiguous/malformed configs.
        if (vectors.isObject() && vectors.size() == 1) {
            JsonNode only = vectors.elements().next();
            if (only.has("size")) {
                return only.path("size").asInt();
            }
        }
        throw KbStoreException.permanent(
                "collection " + name + " does not expose a single vector size", null);
    }

    private void createCollection(String name, int dimension) {
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode vectors = body.putObject("vectors");
        vectors.put("size", dimension);
        vectors.put("distance", "Cosine");
        requireSuccess(
                send("PUT", "/collections/" + encode(name), body), "create collection " + name);
    }

    private void createPayloadIndexes(String name) {
        createPayloadIndex(name, "user_id", "integer");
        createPayloadIndex(name, "document_id", "keyword");
    }

    private void createPayloadIndex(String name, String field, String schema) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("field_name", field);
        body.put("field_schema", schema);
        requireSuccess(
                send("PUT", "/collections/" + encode(name) + "/index?wait=true", body),
                "index " + field + " on " + name);
    }

    private static void requireDimension(String name, int actual, int expected) {
        if (actual != expected) {
            throw new KbDimensionMismatchException(
                    "Qdrant collection " + name + " has dimension " + actual
                            + " but the configured embedding dimension is " + expected);
        }
    }

    private Object lockFor(String name) {
        return collectionLocks.computeIfAbsent(name, ignored -> new Object());
    }

    private QdrantResponse send(String method, String path, ObjectNode body) {
        return send(method, path, body, REQUEST_TIMEOUT);
    }

    private QdrantResponse send(
            String method, String path, ObjectNode body, Duration timeout) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(timeout);
        if (properties.hasApiKey()) {
            request.header("api-key", properties.apiKey());
        }
        try {
            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.header("content-type", "application/json");
                request.method(method, HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
            }
            HttpResponse<String> response = http.send(
                    request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new QdrantResponse(response.statusCode(), parse(response.body()));
        } catch (JsonProcessingException e) {
            throw KbStoreException.permanent("failed to serialize a Qdrant request", e);
        } catch (IOException e) {
            throw KbStoreException.retryable("Qdrant is unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw KbStoreException.retryable("interrupted while calling Qdrant", e);
        }
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(body);
        } catch (IOException e) {
            return objectMapper.createObjectNode();
        }
    }

    private void requireSuccess(QdrantResponse response, String action) {
        if (response.status() >= 200 && response.status() < 300) {
            return;
        }
        String error = response.body().path("status").path("error").asText("");
        String message = "Qdrant " + action + " failed with status " + response.status()
                + (error.isBlank() ? "" : ": " + error);
        boolean retryable = response.status() == 408
                || response.status() == 429
                || response.status() >= 500;
        throw retryable
                ? KbStoreException.retryable(message, null)
                : KbStoreException.permanent(message, null);
    }

    private String baseUrl() {
        String url = properties.url();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private record QdrantResponse(int status, JsonNode body) {}
}
