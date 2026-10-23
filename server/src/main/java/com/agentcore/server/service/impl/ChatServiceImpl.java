package com.agentcore.server.service.impl;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.common.sse.SseFrame;
import com.agentcore.server.common.sse.SseFrameParser;
import com.agentcore.server.config.GatewayProperties;
import com.agentcore.server.dao.SessionDao;
import com.agentcore.server.domain.Session;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Default {@link ChatService}: contract v1 client, session persistence and SSE
 * relay.
 *
 * <p>Java owns the business session ID and turn ID, sends the user identity as
 * a header, validates the turn echoed by {@code run_started}, and synthesizes
 * {@code STREAM_INTERRUPTED} when the gateway stream ends without a terminal.
 */
@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);
    private static final String DEFAULT_TITLE = "新会话";
    private static final String STATUS_ACTIVE = "active";

    private final GatewayProperties gateway;
    private final ObjectMapper objectMapper;
    private final SessionDao sessionDao;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public ChatServiceImpl(GatewayProperties gateway, ObjectMapper objectMapper, SessionDao sessionDao) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
        this.sessionDao = sessionDao;
    }

    @Override
    public String createSession(long userId) {
        String sessionId = UUID.randomUUID().toString();
        Session session = new Session();
        session.setId(sessionId);
        session.setUserId(userId);
        session.setTitle(DEFAULT_TITLE);
        session.setStatus(STATUS_ACTIVE);
        session.setLastActiveAt(nowUtc());
        sessionDao.insert(session);

        // DB first: if the gateway rejects the session, compensate by removing
        // the row instead of leaving an unreachable AgentSession behind.
        try {
            HttpRequest request = gatewaySession(sessionId, userId)
                    .PUT(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = sendForString(request);
            if (response.statusCode() >= 400) {
                throw upstreamError("create session", response);
            }
            return sessionId;
        } catch (RuntimeException ex) {
            try {
                sessionDao.deleteById(sessionId);
            } catch (RuntimeException cleanupFailure) {
                log.warn("failed to remove session {} after gateway error: {}",
                        sessionId, cleanupFailure.getMessage());
            }
            throw ex;
        }
    }

    @Override
    public List<Session> listSessions(
            long userId, LocalDateTime beforeActiveAt, String beforeId, int limit) {
        return sessionDao.listActivePage(userId, beforeActiveAt, beforeId, limit);
    }

    @Override
    public void renameSession(long userId, String sessionId, String title) {
        if (sessionDao.rename(sessionId, userId, title) == 0) {
            throw sessionNotFound();
        }
    }

    @Override
    public void archiveSession(long userId, String sessionId) {
        if (sessionDao.archive(sessionId, userId, nowUtc()) == 0) {
            throw sessionNotFound();
        }

        // The gateway is allowed to forget the AgentSession; a restart already
        // means it has nothing to dispose, so this stays best-effort.
        try {
            HttpRequest request = gatewaySession(sessionId, userId)
                    .DELETE()
                    .build();
            HttpResponse<String> response = sendForString(request);
            if (response.statusCode() >= 400 && response.statusCode() != 404) {
                log.warn("gateway dispose returned {} for archived session {}",
                        response.statusCode(), sessionId);
            }
        } catch (RuntimeException ex) {
            log.warn("gateway dispose failed for archived session {}: {}", sessionId, ex.getMessage());
        }
    }

    @Override
    public PromptStream openPrompt(long userId, String sessionId, String text) {
        if (sessionDao.findActiveOwned(sessionId, userId) == null) {
            throw sessionNotFound();
        }

        String turnId = UUID.randomUUID().toString();
        String requestId = UUID.randomUUID().toString();
        HttpRequest.Builder builder = gatewaySession(sessionId, userId)
                .uri(URI.create(gateway.baseUrl() + "/sessions/" + urlEncode(sessionId) + "/prompt"))
                .header("content-type", "application/json")
                .header("accept", "text/event-stream")
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

    private HttpRequest.Builder gatewaySession(String sessionId, long userId) {
        return HttpRequest.newBuilder(
                        URI.create(gateway.baseUrl() + "/sessions/" + urlEncode(sessionId)))
                .header("X-User-Id", Long.toString(userId));
    }

    private static ApiException sessionNotFound() {
        return new ApiException(ErrorCode.SESSION_NOT_FOUND, "session not found");
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
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
