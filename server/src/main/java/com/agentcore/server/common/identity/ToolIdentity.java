package com.agentcore.server.common.identity;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import org.springframework.http.HttpHeaders;

/**
 * Identity injected by the gateway into tool callbacks. It is read only from
 * headers; tool arguments must never be trusted for identity.
 */
public record ToolIdentity(
        String userId,
        String sessionId,
        String turnId,
        String runId,
        String toolCallId,
        String requestId) {

    public static ToolIdentity from(HttpHeaders headers) {
        return new ToolIdentity(
                required(headers, "X-User-Id"),
                required(headers, "X-Session-Id"),
                required(headers, "X-Turn-Id"),
                required(headers, "X-Run-Id"),
                required(headers, "X-Tool-Call-Id"),
                required(headers, "X-Request-Id"));
    }

    private static String required(HttpHeaders headers, String name) {
        String value = headers.getFirst(name);
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, name + " header is required");
        }
        return value;
    }
}
