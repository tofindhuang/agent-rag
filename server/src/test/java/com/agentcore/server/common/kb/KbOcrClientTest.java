package com.agentcore.server.common.kb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentcore.server.config.KbOcrProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KbOcrClientTest {

    private HttpServer server;
    private volatile int status;
    private volatile String body;
    private volatile long delayMillis;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private KbOcrClient client;

    @BeforeEach
    void setUp() throws IOException {
        status = 200;
        body = "{\"text\":\"识别文本\",\"confidence\":0.91}";
        delayMillis = 0;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ocr", this::handle);
        server.start();
        client = new KbOcrClient(properties(), new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void sendsThePageAndParsesTextConfidenceAndLatency() {
        KbOcrResult result = client.recognize(new byte[] {1, 2, 3}, Duration.ofSeconds(2));

        assertThat(result.text()).isEqualTo("识别文本");
        assertThat(result.confidence()).isEqualTo(0.91);
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(requests).hasValue(1);
        assertThat(authorization.get()).isEqualTo("Bearer secret");
    }

    @Test
    void classifiesBadImageAsPermanent() {
        status = 400;
        body = "{\"error\":{\"code\":\"INVALID_IMAGE\"}}";

        assertThatThrownBy(() -> client.recognize(new byte[] {1}, Duration.ofSeconds(2)))
                .isInstanceOfSatisfying(KbOcrException.class, (error) -> {
                    assertThat(error.retryable()).isFalse();
                    assertThat(error.errorCode()).isEqualTo(KbIngestException.OCR_INVALID_IMAGE);
                });
        assertThat(requests).hasValue(1);
    }

    @Test
    void retriesServerErrorsThenReportsThemRetryable() {
        status = 503;
        body = "{\"error\":{\"code\":\"UNAVAILABLE\"}}";

        assertThatThrownBy(() -> client.recognize(new byte[] {1}, Duration.ofSeconds(2)))
                .isInstanceOfSatisfying(KbOcrException.class, (error) -> {
                    assertThat(error.retryable()).isTrue();
                    assertThat(error.errorCode()).isEqualTo(KbIngestException.OCR_UNAVAILABLE);
                });
        assertThat(requests).hasValue(2);
    }

    @Test
    void classifiesTimeoutsAsRetryable() {
        delayMillis = 400;

        assertThatThrownBy(() -> client.recognize(
                        new byte[] {1}, Duration.ofMillis(80)))
                .isInstanceOfSatisfying(KbOcrException.class, (error) -> {
                    assertThat(error.retryable()).isTrue();
                    assertThat(error.errorCode()).isEqualTo(KbIngestException.OCR_TIMEOUT);
                });
    }

    private KbOcrProperties properties() {
        return new KbOcrProperties(
                true,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "secret",
                100,
                2_000L,
                60_000L,
                10,
                200);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        authorization.set(exchange.getRequestHeaders().getFirst("authorization"));
        exchange.getRequestBody().readAllBytes();
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
