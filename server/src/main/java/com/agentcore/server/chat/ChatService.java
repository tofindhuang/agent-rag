package com.agentcore.server.chat;

import com.agentcore.server.config.GatewayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Thin client for the gateway's contract v0 (gateway/src/index.ts):
 *
 *   POST   /sessions/:id/prompt  -> SSE stream of pi events
 *   POST   /sessions             -> create session
 *   DELETE /sessions/:id         -> dispose session
 */
@Service
public class ChatService {

    private final GatewayProperties gateway;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public ChatService(GatewayProperties gateway, ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    /** Creates a gateway session and returns its ID (also the business session ID). */
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

    /** Streams the gateway's SSE response straight through to the caller. */
    public StreamingResponseBody streamPrompt(String sessionId, String text) {
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
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "gateway unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "interrupted", e);
        }
        if (response.statusCode() != 200) {
            String detail = readFully(response.body());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "gateway error " + response.statusCode() + ": " + detail);
        }
        return out -> {
            // A mid-stream IOException here is a client disconnect; drop it.
            try (InputStream in = response.body()) {
                in.transferTo(out);
                out.flush();
            } catch (IOException e) {
                // no-op: client went away
            }
        };
    }

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
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "gateway unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "interrupted", e);
        }
    }

    private ResponseStatusException upstreamError(String what, HttpResponse<String> response) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "gateway " + what + " failed: " + response.statusCode() + " " + response.body());
    }

    private String toJson(Map<String, String> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String readFully(InputStream in) {
        try (InputStream ignored = in) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(unreadable: " + e.getMessage() + ")";
        }
    }
}
