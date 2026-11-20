package com.agentcore.server.common.kb;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Fetches an HTML URL into a temp file for ingestion. Redirects are disabled
 * so each hop would have to pass {@link KbUrlGuard}; callers get a typed error
 * instead of a silently followed redirect.
 */
@Component
public class KbUrlFetcher {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final KbUrlGuard guard;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public KbUrlFetcher(KbUrlGuard guard) {
        this.guard = guard;
    }

    public FetchedUrl fetch(String rawUrl) {
        URI uri = guard.validate(rawUrl);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("User-Agent", "agent-core-kb/1.0")
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new ApiException(ErrorCode.KB_UNAVAILABLE,
                    "url fetch failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.KB_UNAVAILABLE, "url fetch was interrupted", e);
        }

        int status = response.statusCode();
        if (status >= 300 && status < 400) {
            closeQuietly(response.body());
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "url redirects are not supported; use the final URL");
        }
        if (status >= 500) {
            closeQuietly(response.body());
            throw new ApiException(ErrorCode.KB_UNAVAILABLE,
                    "url returned status " + status);
        }
        if (status < 200 || status >= 300) {
            closeQuietly(response.body());
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "url returned status " + status);
        }

        String contentType = response.headers()
                .firstValue("content-type")
                .orElse(KbFileTypes.HTML_MIME);
        String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!mediaType.equals("text/html") && !mediaType.equals("application/xhtml+xml")) {
            closeQuietly(response.body());
            throw new ApiException(ErrorCode.KB_UNSUPPORTED_FORMAT,
                    "url must return HTML");
        }

        Path temp = null;
        try {
            temp = Files.createTempFile("kb-url-", ".html");
            long size = copyLimited(response.body(), temp);
            if (size == 0) {
                throw new ApiException(ErrorCode.KB_UNSUPPORTED_FORMAT,
                        "url returned an empty body");
            }
            return new FetchedUrl(uri, temp, size, KbFileTypes.HTML_MIME);
        } catch (ApiException e) {
            deleteQuietly(temp);
            throw e;
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new ApiException(ErrorCode.KB_UNAVAILABLE,
                    "url fetch failed: " + e.getMessage(), e);
        }
    }

    private static long copyLimited(InputStream body, Path target) throws IOException {
        long total = 0;
        byte[] buffer = new byte[8192];
        try (InputStream input = body; OutputStream output = Files.newOutputStream(target)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > KbFileTypes.MAX_URL_BYTES) {
                    throw new ApiException(ErrorCode.KB_FILE_TOO_LARGE,
                            "url content exceeds the 5 MB limit");
                }
                output.write(buffer, 0, read);
            }
        }
        return total;
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // The response is already unusable; nothing to recover here.
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort; the OS temp cleaner is the fallback.
        }
    }

    public record FetchedUrl(URI uri, Path file, long size, String mime) {}
}
