package com.agentcore.server.common.error;

/**
 * Stable error envelope:
 * {@code {"error":{"code":"...","message":"...","retryable":false}}}.
 */
public record ApiError(ErrorBody error) {

    public static ApiError of(ErrorCode code, String message) {
        return new ApiError(new ErrorBody(code.name(), message, code.retryable()));
    }

    public record ErrorBody(String code, String message, boolean retryable) {}
}
