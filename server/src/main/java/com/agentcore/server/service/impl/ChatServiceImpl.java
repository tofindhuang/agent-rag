package com.agentcore.server.service.impl;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.config.GatewayProperties;
import com.agentcore.server.service.ChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Default {@link ChatService} implementation: chat orchestration and the only
 * place that talks HTTP to the gateway.
 *
 * <p>Controllers never build gateway requests themselves. Contract v0 is
 * implemented today; v1 endpoints (idempotent session PUT, identity headers,
 * turnId) land with the contract migration.
 */
@Service
public class ChatServiceImpl implements ChatService {

    private final GatewayProperties gateway;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public ChatServiceImpl(GatewayProperties gateway, ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    /** Creates a gateway session and returns its ID (also the business session ID). */
    @Override
    public String createSession(String userId) {
        String sessionId = UUID.randomUUID().toString();
        HttpRequest request = HttpRequest.newBuilder(URI.create(gateway.baseUrl() + "/sessions"))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(Map.of("sessionId", sessionId, "userId", userId))))
                .build();
        HttpResponse<String> response = sendForString(request);
        if (response.statusCode() >= 400) {
            throw upstreamError("create session", response);
        }
        return sessionId;
    }

    /**
     * Opens the gateway prompt stream. The caller owns the returned stream and
     * must close it when the client is done.
     */
    @Override
    public InputStream openPrompt(String sessionId, String text) {
        URI uri = URI.create(gateway.baseUrl() + "/sessions/" + urlEncode(sessionId) + "/prompt");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .header("content-type", "application/json")
                .header("accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(Map.of("text", text))));
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
        return response.body();
    }

    @Override
    public void disposeSession(String sessionId) {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(gateway.baseUrl() + "/sessions/" + urlEncode(sessionId)))
                .DELETE()
                .build();
        HttpResponse<String> response = sendForString(request);
        if (response.statusCode() >= 400 && response.statusCode() != 404) {
            throw upstreamError("dispose session", response);
        }
    }

    private HttpResponse<String> sendForString(HttpRequest request) {
        try {
            HttpRequest.Builder authorized = gateway.hasToken()
                    ? request.newBuilder().header("authorization", "Bearer " + gateway.apiToken())
                    : request.newBuilder();
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
