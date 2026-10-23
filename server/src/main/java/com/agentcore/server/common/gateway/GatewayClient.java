package com.agentcore.server.common.gateway;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.config.GatewayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Single HTTP boundary for the Java → gateway contract.
 *
 * <p>Owns the base URL, the optional shared bearer token, the connect timeout
 * and the transport-error mapping, so services never build their own client.
 * Business mapping of gateway error codes stays with each use case: this class
 * only extracts the contract envelope.
 */
@Component
public class GatewayClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final GatewayProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    public GatewayClient(GatewayProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public String baseUrl() {
        return properties.baseUrl();
    }

    /** Builder with the base URL and shared token already applied. */
    public HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create(properties.baseUrl() + path));
        if (properties.hasToken()) {
            builder.header("authorization", "Bearer " + properties.apiToken());
        }
        return builder;
    }

    public HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw transportFailure("gateway unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw transportFailure("interrupted while calling gateway", e);
        }
    }

    /** Prompt calls: the caller owns the stream and must close it. */
    public HttpResponse<InputStream> sendStreaming(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw transportFailure("gateway unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw transportFailure("interrupted while calling gateway", e);
        }
    }

    /** Parses the contract envelope; empty fields when the body is not JSON. */
    public GatewayError errorBody(String body) {
        try {
            JsonNode error = objectMapper.readTree(body).path("error");
            return new GatewayError(error.path("code").asText(""), error.path("message").asText(""));
        } catch (IOException ignored) {
            return new GatewayError("", "");
        }
    }

    private static ApiException transportFailure(String message, Exception cause) {
        return new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, message, cause);
    }

    /** Contract error envelope fields; either may be empty. */
    public record GatewayError(String code, String message) {}
}
