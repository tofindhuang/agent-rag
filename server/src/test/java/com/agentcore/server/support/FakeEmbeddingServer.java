package com.agentcore.server.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Tiny OpenAI-compatible embedding endpoint for KB integration tests. */
public final class FakeEmbeddingServer {

    public enum Mode {
        NORMAL,
        RATE_LIMITED,
        BAD_REQUEST,
        WRONG_DIMENSION,
        ONE_BASED_INDEX,
        SLOW
    }

    private final HttpServer server;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile int dimension;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile Mode mode = Mode.NORMAL;

    private FakeEmbeddingServer(int dimension) throws IOException {
        this.dimension = dimension;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/v1/embeddings", this::handle);
        this.server.createContext("/v1/models", this::models);
        this.server.start();
    }

    public static FakeEmbeddingServer start(int dimension) {
        try {
            return new FakeEmbeddingServer(dimension);
        } catch (IOException e) {
            throw new IllegalStateException("cannot start fake embedding server", e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    public int requests() {
        return requests.get();
    }

    public void mode(Mode mode) {
        this.mode = mode;
    }

    /** Lets a settings probe test change the vector size the fake returns. */
    public void dimension(int dimension) {
        this.dimension = dimension;
    }

    public void reset() {
        this.mode = Mode.NORMAL;
        this.requests.set(0);
    }

    private void models(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        Mode current = mode;
        if (current == Mode.RATE_LIMITED) {
            respond(exchange, 429, "{\"error\":\"rate limited\"}");
            return;
        }
        if (current == Mode.BAD_REQUEST) {
            respond(exchange, 400, "{\"error\":\"bad request\"}");
            return;
        }
        respond(exchange, 200, "{\"data\":[{\"id\":\"test-embedding-model\"},{\"id\":\"alt-embedding-model\"}]}");
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        JsonNode body = objectMapper.readTree(exchange.getRequestBody());
        JsonNode input = body.path("input");
        int count = input.isArray() ? input.size() : 0;

        Mode current = mode;
        if (current == Mode.SLOW) {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (current == Mode.RATE_LIMITED) {
            respond(exchange, 429, "{\"error\":\"rate limited\"}");
            return;
        }
        if (current == Mode.BAD_REQUEST) {
            respond(exchange, 400, "{\"error\":\"bad input\"}");
            return;
        }

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode data = response.putArray("data");
        int size = current == Mode.WRONG_DIMENSION ? 3 : dimension;
        for (int i = 0; i < count; i++) {
            ObjectNode item = data.addObject();
            item.put("index", current == Mode.ONE_BASED_INDEX ? i + 1 : i);
            ArrayNode embedding = item.putArray("embedding");
            for (int j = 0; j < size; j++) {
                embedding.add(j == 0 ? (i + 1) : 0f);
            }
        }
        respond(exchange, 200, objectMapper.writeValueAsString(response));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
