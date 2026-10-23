package com.agentcore.server.common.kb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.config.KbProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KbUrlFetcherTest {

    private HttpServer server;
    private KbUrlFetcher fetcher;
    private final AtomicReference<Handler> handler = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", (exchange) -> handler.get().handle(exchange));
        server.start();
        KbProperties properties = new KbProperties(
                true, true, 200, 1L << 30, 3, null, null);
        fetcher = new KbUrlFetcher(new KbUrlGuard(properties));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void fetchesHtmlIntoATempFile() throws Exception {
        byte[] html = "<html><body>hello</body></html>".getBytes(StandardCharsets.UTF_8);
        handler.set((exchange) -> respond(exchange, 200, "text/html; charset=utf-8", html));

        KbUrlFetcher.FetchedUrl fetched = fetcher.fetch(url("/page"));

        try {
            assertThat(fetched.size()).isEqualTo(html.length);
            assertThat(fetched.mime()).isEqualTo(KbFileTypes.HTML_MIME);
            assertThat(Files.readAllBytes(fetched.file())).isEqualTo(html);
        } finally {
            Files.deleteIfExists(fetched.file());
        }
    }

    @Test
    void rejectsNonHtmlAndRedirectsAndServerErrors() {
        handler.set((exchange) -> respond(exchange, 200, "application/pdf", new byte[] {1}));
        assertThatThrownBy(() -> fetcher.fetch(url("/pdf")))
                .isInstanceOf(ApiException.class)
                .extracting((error) -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.KB_UNSUPPORTED_FORMAT);

        handler.set((exchange) -> respond(exchange, 302, "text/html", new byte[0]));
        assertThatThrownBy(() -> fetcher.fetch(url("/redirect")))
                .isInstanceOf(ApiException.class)
                .extracting((error) -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.INVALID_REQUEST);

        handler.set((exchange) -> respond(exchange, 503, "text/html", new byte[0]));
        assertThatThrownBy(() -> fetcher.fetch(url("/down")))
                .isInstanceOf(ApiException.class)
                .extracting((error) -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.KB_UNAVAILABLE);
    }

    @Test
    void enforcesTheFiveMegabyteUrlLimit() {
        byte[] huge = new byte[(int) KbFileTypes.MAX_URL_BYTES + 1];
        handler.set((exchange) -> respond(exchange, 200, "text/html", huge));

        assertThatThrownBy(() -> fetcher.fetch(url("/huge")))
                .isInstanceOf(ApiException.class)
                .extracting((error) -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.KB_FILE_TOO_LARGE);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static void respond(
            HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
