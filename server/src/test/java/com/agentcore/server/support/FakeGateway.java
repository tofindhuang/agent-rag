package com.agentcore.server.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal in-process stand-in for the Node gateway. Health returns a compatible
 * contract version so startup verification is exercised for real; session
 * lifecycle calls are recorded for identity assertions.
 */
public final class FakeGateway {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final AtomicReference<String> lastCreateSessionUserId = new AtomicReference<>();
    private final AtomicReference<String> lastTurnId = new AtomicReference<>();
    private final AtomicReference<String> promptMode = new AtomicReference<>("complete");
    private final AtomicInteger putCount = new AtomicInteger();
    private final AtomicReference<String> probeMode = new AtomicReference<>("ok");
    private final AtomicReference<String> lastProbeApiKey = new AtomicReference<>();
    private final AtomicReference<String> lastProbeApi = new AtomicReference<>();
    private final AtomicReference<String> lastProbeBaseUrl = new AtomicReference<>();
    private final AtomicReference<String> lastModelId = new AtomicReference<>();
    private final AtomicReference<String> lastModelApiKey = new AtomicReference<>();
    private final AtomicInteger deleteCount = new AtomicInteger();
    private final AtomicInteger deleteFilesCount = new AtomicInteger();
    private final AtomicReference<String> lastDeleteFilesUserId = new AtomicReference<>();
    private final AtomicReference<String> deleteFilesMode = new AtomicReference<>("ok");

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

    public String lastTurnId() {
        return lastTurnId.get();
    }

    public void setPromptMode(String mode) {
        promptMode.set(mode);
    }

    public int putCount() {
        return putCount.get();
    }

    public void resetPutCount() {
        putCount.set(0);
    }

    public void setProbeMode(String mode) {
        probeMode.set(mode);
    }

    public String lastProbeApiKey() {
        return lastProbeApiKey.get();
    }

    public String lastProbeApi() {
        return lastProbeApi.get();
    }

    public String lastProbeBaseUrl() {
        return lastProbeBaseUrl.get();
    }

    public String lastModelId() {
        return lastModelId.get();
    }

    public String lastModelApiKey() {
        return lastModelApiKey.get();
    }

    public int deleteCount() {
        return deleteCount.get();
    }

    public int deleteFilesCount() {
        return deleteFilesCount.get();
    }

    public String lastDeleteFilesUserId() {
        return lastDeleteFilesUserId.get();
    }

    /** "ok" (204), "fail" (502) or "forbidden" (403) for DELETE /sessions/:id/files. */
    public void setDeleteFilesMode(String mode) {
        deleteFilesMode.set(mode);
    }

    public void resetDeleteFiles() {
        deleteFilesCount.set(0);
        lastDeleteFilesUserId.set(null);
        deleteFilesMode.set("ok");
    }

    public void resetModelCapture() {
        lastModelId.set(null);
        lastModelApiKey.set(null);
        deleteCount.set(0);
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
                putCount.incrementAndGet();
                lastCreateSessionUserId.set(exchange.getRequestHeaders().getFirst("X-User-Id"));
                String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                JsonNode model = raw.isBlank() ? null : MAPPER.readTree(raw).path("model");
                if (model != null && !model.isMissingNode()) {
                    String modelId = model.path("modelId").asText("");
                    lastModelId.set(modelId);
                    lastModelApiKey.set(model.path("apiKey").asText(""));
                    sendJson(exchange, 200,
                            "{\"sessionId\":\"s\",\"created\":true,\"restored\":false,"
                                    + "\"model\":{\"provider\":\"Prov\",\"id\":\"" + modelId
                                    + "\",\"name\":\"" + model.path("displayName").asText(modelId) + "\"}}");
                    return;
                }
                sendJson(exchange, 200,
                        "{\"sessionId\":\"s\",\"created\":true,\"restored\":false,"
                                + "\"model\":{\"provider\":\"system\",\"id\":\"system-model\","
                                + "\"name\":\"System Model\"}}");
                return;
            }
            if (path.startsWith("/sessions/") && path.endsWith("/files") && method.equals("DELETE")) {
                deleteFilesCount.incrementAndGet();
                lastDeleteFilesUserId.set(exchange.getRequestHeaders().getFirst("X-User-Id"));
                if ("fail".equals(deleteFilesMode.get())) {
                    sendJson(exchange, 502,
                            "{\"error\":{\"code\":\"INTERNAL\",\"message\":\"disk\",\"retryable\":true}}");
                    return;
                }
                if ("forbidden".equals(deleteFilesMode.get())) {
                    sendJson(exchange, 403,
                            "{\"error\":{\"code\":\"USER_MISMATCH\",\"message\":\"owner\",\"retryable\":false}}");
                    return;
                }
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (path.startsWith("/sessions/") && method.equals("DELETE")) {
                deleteCount.incrementAndGet();
                sendJson(exchange, 200, "{}");
                return;
            }
            if (path.endsWith("/prompt") && method.equals("POST")) {
                handlePrompt(exchange);
                return;
            }
            if (path.equals("/providers/probe") && method.equals("POST")) {
                handleProbe(exchange);
                return;
            }
            sendJson(exchange, 404, "{\"error\":\"not found\"}");
        } finally {
            exchange.close();
        }
    }

    private void handleProbe(HttpExchange exchange) throws IOException {
        String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode body = MAPPER.readTree(raw);
        lastProbeApi.set(body.path("api").asText(""));
        lastProbeBaseUrl.set(body.path("baseUrl").asText(""));
        lastProbeApiKey.set(body.path("apiKey").asText(""));
        String mode = probeMode.get();
        if ("auth".equals(mode)) {
            sendJson(exchange, 502,
                    "{\"error\":{\"code\":\"PROVIDER_AUTH_FAILED\",\"message\":\"bad key\",\"retryable\":false}}");
            return;
        }
        if ("failed".equals(mode)) {
            sendJson(exchange, 502,
                    "{\"error\":{\"code\":\"PROVIDER_PROBE_FAILED\",\"message\":\"bad response\",\"retryable\":false}}");
            return;
        }
        if ("unreachable".equals(mode)) {
            sendJson(exchange, 502,
                    "{\"error\":{\"code\":\"PROVIDER_UNREACHABLE\",\"message\":\"down\",\"retryable\":true}}");
            return;
        }
        sendJson(exchange, 200,
                "{\"models\":[{\"id\":\"model-a\",\"name\":\"Model A\"},{\"id\":\"model-b\",\"name\":\"Model B\"}]}");
    }

    private void handlePrompt(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String turnId = turnIdOf(body);
        lastTurnId.set(turnId);
        String mode = promptMode.get();

        if ("reject".equals(mode)) {
            sendJson(exchange, 500, "{\"error\":{\"code\":\"INTERNAL\",\"message\":\"boom\",\"retryable\":false}}");
            return;
        }

        StringBuilder sse = new StringBuilder();
        appendFrame(sse, 1, "run_started",
                "{\"runId\":\"run-1\",\"turnId\":\"" + turnId
                        + "\",\"sessionId\":\"s\",\"contractVersion\":\"1\"}");
        if ("errorOnly".equals(mode)) {
            appendFrame(sse, 2, "run_settled",
                    "{\"status\":\"error\",\"turnId\":\"" + turnId
                            + "\",\"error\":{\"code\":\"MODEL_ERROR\",\"message\":\"upstream 429\",\"retryable\":true}}");
        } else if ("interrupted".equals(mode)) {
            appendFrame(sse, 2, "thinking_delta", "{\"delta\":\"partial think\"}");
            appendFrame(sse, 3, "text_delta", "{\"delta\":\"partial text\"}");
        } else if ("toolInterrupted".equals(mode)) {
            appendFrame(sse, 2, "message_end", assistantMessage(1, "first answer", "toolUse",
                    "[{\"toolCallId\":\"call-1\",\"name\":\"run_code\",\"args\":{}}]", 20, 10));
            appendFrame(sse, 3, "tool_result",
                    "{\"toolCallId\":\"call-1\",\"name\":\"run_code\",\"isError\":false,"
                            + "\"durationMs\":12,\"content\":\"42\",\"truncated\":false}");
            appendFrame(sse, 4, "text_delta", "{\"delta\":\"after tool\"}");
        } else if ("tool".equals(mode) || "bigtool".equals(mode)) {
            appendFrame(sse, 2, "message_end", assistantMessage(1, "", "toolUse",
                    "[{\"toolCallId\":\"call-1\",\"name\":\"run_code\",\"args\":{}}]", 20, 10));
            String content = "bigtool".equals(mode)
                    ? "x".repeat(70_000)
                    : "42";
            appendFrame(sse, 3, "tool_result",
                    "{\"toolCallId\":\"call-1\",\"name\":\"run_code\",\"isError\":false,"
                            + "\"durationMs\":12,\"content\":\"" + content + "\",\"truncated\":false}");
            appendFrame(sse, 4, "message_end", assistantMessage(2, "done", "stop", "[]", 30, 5));
            appendFrame(sse, 5, "run_settled", "{\"status\":\"completed\",\"turnId\":\"" + turnId + "\"}");
        } else {
            appendFrame(sse, 2, "text_delta", "{\"delta\":\"hello \"}");
            appendFrame(sse, 3, "text_delta", "{\"delta\":\"world\"}");
            appendFrame(sse, 4, "message_end", assistantMessage(1, "hello world", "stop", "[]", 10, 5));
            appendFrame(sse, 5, "run_settled", "{\"status\":\"completed\",\"turnId\":\"" + turnId + "\"}");
        }
        sendSse(exchange, sse.toString());
    }

    private static String assistantMessage(
            int seqInTurn, String text, String stopReason, String toolCalls, long input, long output) {
        return "{\"seqInTurn\":" + seqInTurn + ",\"role\":\"assistant\",\"text\":\""
                + text + "\",\"thinking\":\"why\",\"toolCalls\":" + toolCalls
                + ",\"usage\":{\"input\":" + input + ",\"output\":" + output
                + "},\"stopReason\":\"" + stopReason + "\"}";
    }

    private static String turnIdOf(String body) {
        int index = body.indexOf("\"turnId\"");
        if (index < 0) return "";
        int colon = body.indexOf(':', index);
        int start = body.indexOf('"', colon) + 1;
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }

    private static void appendFrame(StringBuilder sse, int seq, String event, String data) {
        sse.append("id: run-1:").append(seq).append('\n')
                .append("event: ").append(event).append('\n')
                .append("data: ").append(data).append("\n\n");
    }

    private static void sendSse(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
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
