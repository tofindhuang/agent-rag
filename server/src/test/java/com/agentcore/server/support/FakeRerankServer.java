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

/** Tiny OpenAI-compatible rerank endpoint for KB settings probe tests. */
public final class FakeRerankServer {

    public enum Mode {
        NORMAL,
        BAD_REQUEST,
        INVALID_RESPONSE
    }

    private final HttpServer server;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile Mode mode = Mode.NORMAL;

    private FakeRerankServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/v1/rerank", this::handle);
        this.server.start();
    }

    public static FakeRerankServer start() {
        try {
            return new FakeRerankServer();
        } catch (IOException e) {
            throw new IllegalStateException("cannot start fake rerank server", e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    public void mode(Mode mode) {
        this.mode = mode;
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        JsonNode body = objectMapper.readTree(exchange.getRequestBody());
        JsonNode documents = body.path("documents");
        int count = documents.isArray() ? documents.size() : 0;

        Mode current = mode;
        if (current == Mode.BAD_REQUEST) {
            respond(exchange, 400, "{\"error\":\"bad input\"}");
            return;
        }
        if (current == Mode.INVALID_RESPONSE) {
            respond(exchange, 200, "{\"results\":\"not-an-array\"}");
            return;
        }

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode results = response.putArray("results");
        for (int i = count - 1; i >= 0; i--) {
            ObjectNode item = results.addObject();
            item.put("index", i);
            item.put("relevance_score", 0.9 - (count - 1 - i) * 0.1);
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
