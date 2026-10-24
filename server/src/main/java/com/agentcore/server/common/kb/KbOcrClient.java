package com.agentcore.server.common.kb;

import com.agentcore.server.config.KbOcrProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Internal HTTP client for the OCR sidecar (ADR-0014). The endpoint is
 * deployment configuration, not user input, so it does not go through the
 * user-facing SSRF guard. Each page is attempted at most twice; the ingestion
 * ladder owns whole-document retries.
 */
@Component
public class KbOcrClient implements OcrRecognizer {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final int MAX_ATTEMPTS = 2;

    private final KbOcrProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    public KbOcrClient(KbOcrProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public KbOcrResult recognize(byte[] png, Duration timeout) {
        if (!properties.configured()) {
            throw KbOcrException.permanent(
                    KbIngestException.OCR_UNAVAILABLE, "OCR sidecar is not configured");
        }
        if (png == null || png.length == 0) {
            throw KbOcrException.permanent(
                    KbIngestException.OCR_INVALID_IMAGE, "rendered page is empty");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw KbOcrException.retryable(
                    KbIngestException.OCR_TIMEOUT, "OCR budget is exhausted", null);
        }

        KbOcrException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return call(png, timeout);
            } catch (KbOcrException e) {
                last = e;
                if (!e.retryable() || attempt == MAX_ATTEMPTS) {
                    throw e;
                }
            }
        }
        throw last == null
                ? KbOcrException.retryable(
                        KbIngestException.OCR_UNAVAILABLE, "OCR failed", null)
                : last;
    }

    private KbOcrResult call(byte[] png, Duration timeout) {
        URI uri = URI.create(endpoint());
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("content-type", "image/png")
                .header("accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(png));
        if (properties.token() != null && !properties.token().isBlank()) {
            request.header("authorization", "Bearer " + properties.token());
        }

        long started = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(
                    StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw KbOcrException.retryable(
                    KbIngestException.OCR_TIMEOUT, "OCR request timed out", e);
        } catch (IOException e) {
            throw KbOcrException.retryable(
                    KbIngestException.OCR_UNAVAILABLE,
                    "OCR sidecar is unreachable: " + e.getMessage(),
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw KbOcrException.retryable(
                    KbIngestException.OCR_UNAVAILABLE, "OCR request was interrupted", e);
        }
        long latencyMs = (System.nanoTime() - started) / 1_000_000L;

        int status = response.statusCode();
        if (status == 200) {
            return parse(response.body(), latencyMs);
        }
        if (status == 400 || status == 413) {
            throw KbOcrException.permanent(
                    KbIngestException.OCR_INVALID_IMAGE,
                    "OCR sidecar rejected the rendered page (status " + status + ")");
        }
        if (status == 408 || status == 429 || status >= 500) {
            throw KbOcrException.retryable(
                    KbIngestException.OCR_UNAVAILABLE,
                    "OCR sidecar returned status " + status,
                    null);
        }
        throw KbOcrException.permanent(
                KbIngestException.OCR_UNAVAILABLE,
                "OCR sidecar rejected the request (status " + status + ")");
    }

    private KbOcrResult parse(String body, long latencyMs) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException e) {
            throw KbOcrException.permanent(
                    KbIngestException.OCR_UNAVAILABLE, "OCR response is not valid JSON", e);
        }
        JsonNode textNode = root.path("text");
        if (!textNode.isTextual()) {
            throw KbOcrException.permanent(
                    KbIngestException.OCR_UNAVAILABLE, "OCR response is missing text");
        }
        double confidence = root.path("confidence").asDouble(0.0);
        return new KbOcrResult(textNode.asText(""), confidence, latencyMs);
    }

    private String endpoint() {
        String base = properties.baseUrl();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/ocr";
    }
}
