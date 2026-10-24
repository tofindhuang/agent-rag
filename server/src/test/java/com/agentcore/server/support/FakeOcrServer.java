package com.agentcore.server.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Minimal OCR sidecar for Java integration tests (contract of ADR-0014). */
public final class FakeOcrServer {

    private final HttpServer server;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger requests = new AtomicInteger();

    private FakeOcrServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/ocr", this::handle);
        this.server.createContext("/health", this::health);
        this.server.start();
    }

    public static FakeOcrServer start() {
        try {
            return new FakeOcrServer();
        } catch (IOException e) {
            throw new IllegalStateException("cannot start fake OCR server", e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public int requests() {
        return requests.get();
    }

    public void reset() {
        requests.set(0);
    }

    private void health(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "{\"ok\":true,\"engine\":\"fake\",\"version\":\"test\"}");
    }

    private void handle(HttpExchange exchange) throws IOException {
        int request = requests.incrementAndGet();
        exchange.getRequestBody().readAllBytes();
        String text = "OCR 扫描页 " + request + " 的识别文本";
        ObjectNode response = objectMapper.createObjectNode();
        response.put("text", text);
        response.put("confidence", 0.91);
        ArrayNode lines = response.putArray("lines");
        ObjectNode line = lines.addObject();
        line.put("text", text);
        line.put("confidence", 0.91);
        respond(exchange, 200, objectMapper.writeValueAsString(response));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
