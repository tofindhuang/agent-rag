package com.agentcore.server.service.impl;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.common.sse.SseFrame;
import com.agentcore.server.common.sse.SseFrameParser;
import com.agentcore.server.config.GatewayProperties;
import com.agentcore.server.service.ChatService;
import com.agentcore.server.service.PromptStream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Default {@link ChatService}: contract v1 client and SSE relay.
 *
 * <p>Java owns the business session ID and turn ID, sends the user identity as
 * a header, validates the turn echoed by {@code run_started}, and synthesizes
 * {@code STREAM_INTERRUPTED} when the gateway stream ends without a terminal.
 */
@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    private final GatewayProperties gateway;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    // M0 only: no persistence yet; M1 replaces this with the sessions table.
    private final ConcurrentMap<String, String> sessionUsers = new ConcurrentHashMap<>();

    public ChatServiceImpl(GatewayProperties gateway, ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    @Override
    public String createSession(String userId) {
        String sessionId = UUID.randomUUID().toString();
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(gateway.baseUrl() + "/sessions/" + urlEncode(sessionId)))
                .header("X-User-Id", userId)
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = sendForString(request);
        if (response.statusCode() >= 400) {
            throw upstreamError("create session", response);
        }
        sessionUsers.put(sessionId, userId);
        return sessionId;
    }

    @Override
    public PromptStream openPrompt(String sessionId, String text) {
        String userId = sessionUsers.get(sessionId);
        if (userId == null) {
            throw new ApiException(ErrorCode.SESSION_NOT_FOUND, "session not found");
        }

        String turnId = UUID.randomUUID().toString();
        String requestId = UUID.randomUUID().toString();
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create(gateway.baseUrl() + "/sessions/" + urlEncode(sessionId) + "/prompt"))
                .header("content-type", "application/json")
                .header("accept", "text/event-stream")
                .header("X-User-Id", userId)
                .header("X-Request-Id", requestId)
                .POST(HttpRequest.BodyPublishers.ofString(
                        toJson(Map.of("text", text, "turnId", turnId))));
        if (gateway.hasToken()) {
            builder.header("authorization", "Bearer " + gateway.apiToken());
        }

        HttpResponse<InputStream> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "gateway unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "interrupted while calling gateway", e);
        }
        if (response.statusCode() != 200) {
            String detail = readFully(response.body());
            throw new ApiException(ErrorCode.UPSTREAM_ERROR,
                    "gateway error " + response.statusCode() + ": " + detail);
        }
        return new PromptStream(response.body(), turnId, requestId);
    }

    @Override
    public void relayPrompt(PromptStream prompt, OutputStream out) throws IOException {
        boolean terminal = false;
        String runId = "unknown";
        long seq = 0;

        try (InputStream body = prompt.body()) {
            SseFrameParser parser = new SseFrameParser(body);
            SseFrame frame;
            while ((frame = parser.read()) != null) {
                if (frame.id() == null) {
                    seq += 1;
                } else {
                    int colon = frame.id().lastIndexOf(':');
                    if (colon > 0) {
                        runId = frame.id().substring(0, colon);
                        try {
                            seq = Long.parseLong(frame.id().substring(colon + 1));
                        } catch (NumberFormatException e) {
                            seq += 1;
                        }
                    } else {
                        seq += 1;
                    }
                }

                if ("run_started".equals(frame.event())) {
                    String echoedTurnId = readTurnId(frame.data());
                    if (!prompt.turnId().equals(echoedTurnId)) {
                        log.warn("gateway run_started turnId mismatch: expected {}, got {}",
                                prompt.turnId(), echoedTurnId);
                        writeTerminal(out, runId, seq + 1, prompt.turnId(),
                                "STREAM_INTERRUPTED", "run_started turnId mismatch");
                        return;
                    }
                }

                out.write(frame.raw().getBytes(StandardCharsets.UTF_8));
                out.flush();

                if ("run_settled".equals(frame.event())) {
                    terminal = true;
                    break;
                }
            }
        } catch (IOException e) {
            if (!terminal) {
                try {
                    writeTerminal(out, runId, seq + 1, prompt.turnId(),
                            "STREAM_INTERRUPTED", "连接中断，未收到终态");
                } catch (IOException ignored) {
                    // client is gone as well; nothing else to do
                }
            }
            return;
        }

        if (!terminal) {
            writeTerminal(out, runId, seq + 1, prompt.turnId(),
                    "STREAM_INTERRUPTED", "连接中断，未收到终态");
        }
    }

    @Override
    public void disposeSession(String sessionId) {
        String userId = sessionUsers.remove(sessionId);
        if (userId == null) return;

        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(gateway.baseUrl() + "/sessions/" + urlEncode(sessionId)))
                .header("X-User-Id", userId)
                .DELETE()
                .build();
        HttpResponse<String> response = sendForString(request);
        if (response.statusCode() >= 400 && response.statusCode() != 404) {
            throw upstreamError("dispose session", response);
        }
    }

    private String readTurnId(String data) throws IOException {
        if (data == null) return "";
        JsonNode node = objectMapper.readTree(data);
        return node.path("turnId").asText("");
    }

    private void writeTerminal(
            OutputStream out,
            String runId,
            long seq,
            String turnId,
            String code,
            String message) throws IOException {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("retryable", false);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "error");
        data.put("turnId", turnId);
        data.put("error", error);

        String frame = "id: " + runId + ":" + seq + "\nevent: run_settled\ndata: "
                + objectMapper.writeValueAsString(data) + "\n\n";
        out.write(frame.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private HttpResponse<String> sendForString(HttpRequest request) {
        HttpRequest.Builder authorized = HttpRequest.newBuilder(request, (name, value) -> true);
        if (gateway.hasToken()) {
            authorized.header("authorization", "Bearer " + gateway.apiToken());
        }
        try {
            return http.send(authorized.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "gateway unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "interrupted while calling gateway", e);
        }
    }

    private ApiException upstreamError(String what, HttpResponse<String> response) {
        return new ApiException(ErrorCode.UPSTREAM_ERROR,
                "gateway " + what + " failed: " + response.statusCode() + " " + response.body());
    }

    private String toJson(Map<String, String> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL, "failed to serialize gateway request", e);
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String readFully(InputStream in) {
        try (InputStream ignored = in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(unreadable: " + e.getMessage() + ")";
        }
    }
}
