package com.agentcore.server.common.error;

/**
 * Stable error codes returned by the business layer.
 *
 * <p>Codes shared with the Java↔gateway contract keep the same names (see
 * docs/specs/2026-10-23-m0-closeout.md). Business-only codes
 * ({@code NOT_FOUND}, {@code CONFLICT}, {@code NOT_IMPLEMENTED},
 * {@code UPSTREAM_ERROR}) describe local API semantics.
 */
public enum ErrorCode {
    INVALID_REQUEST(400, false),
    UNAUTHORIZED(401, false),
    USER_MISMATCH(403, false),
    FORBIDDEN(403, false),
    NOT_FOUND(404, false),
    SESSION_NOT_FOUND(404, false),
    SESSION_CONFLICT(409, false),
    SESSION_PURGE_FAILED(502, true),
    SESSION_PURGE_BLOCKED(409, false),
    EMAIL_ALREADY_EXISTS(409, false),
    MODEL_NOT_CONFIGURED(409, false),
    MODEL_UNAVAILABLE(409, false),
    MODEL_SWITCH_NOT_ALLOWED(409, false),
    CONFLICT(409, false),
    RUN_IN_PROGRESS(409, false),
    NO_ACTIVE_RUN(409, false),
    INTERNAL(500, false),
    NOT_IMPLEMENTED(501, false),
    PROVIDER_UNREACHABLE(502, true),
    PROVIDER_AUTH_FAILED(502, false),
    PROVIDER_PROBE_FAILED(502, false),
    UPSTREAM_ERROR(502, false),
    UPSTREAM_UNAVAILABLE(503, true);

    private final int status;
    private final boolean retryable;

    ErrorCode(int status, boolean retryable) {
        this.status = status;
        this.retryable = retryable;
    }

    public int status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }

    public static ErrorCode fromStatus(int status) {
        return switch (status) {
            case 400 -> INVALID_REQUEST;
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 409 -> CONFLICT;
            case 501 -> NOT_IMPLEMENTED;
            case 502 -> UPSTREAM_ERROR;
            case 503 -> UPSTREAM_UNAVAILABLE;
            default -> status >= 500 ? INTERNAL : INVALID_REQUEST;
        };
    }
}
