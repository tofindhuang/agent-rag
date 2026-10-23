package com.agentcore.server.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal in-process stand-in for the Node gateway. Health returns a compatible
 * contract version so startup verification is exercised for real; session
 * lifecycle calls are recorded for identity assertions.
 */
public final class FakeGateway {

    private final HttpServer server;
    private final AtomicReference<String> lastCreateSessionUserId = new AtomicReference<>();

    public FakeGateway() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to start fake gateway", ex);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public String lastCreateSessionUserId() {
        return lastCreateSessionUserId.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if (path.equals("/health")) {
                sendJson(exchange, 200, "{\"contractVersion\":\"1.0\"}");
                return;
            }
            if (path.startsWith("/sessions/") && method.equals("PUT")) {
                lastCreateSessionUserId.set(exchange.getRequestHeaders().getFirst("X-User-Id"));
                sendJson(exchange, 200, "{}");
                return;
            }
            if (path.startsWith("/sessions/") && method.equals("DELETE")) {
                sendJson(exchange, 200, "{}");
                return;
            }
            sendJson(exchange, 404, "{\"error\":\"not found\"}");
        } finally {
            exchange.close();
        }
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
