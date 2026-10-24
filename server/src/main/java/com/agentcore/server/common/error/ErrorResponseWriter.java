package com.agentcore.server.common.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** Shared JSON envelope writer for filters and the security entry points. */
public final class ErrorResponseWriter {

    private ErrorResponseWriter() {
    }

    public static void write(
            HttpServletResponse response,
            ObjectMapper objectMapper,
            ErrorCode code,
            String message,
            Long retryAfterSeconds)
            throws IOException {
        response.setStatus(code.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (retryAfterSeconds != null) {
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        }
        objectMapper.writeValue(response.getOutputStream(), ApiError.of(code, message));
    }

    public static void write(
            HttpServletResponse response,
            ObjectMapper objectMapper,
            ErrorCode code,
            String message)
            throws IOException {
        write(response, objectMapper, code, message, null);
    }
}
