package com.agentcore.server.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Maps exceptions to the stable error envelope. New code should throw
 * {@link ApiException}; {@link ResponseStatusException} is handled as a
 * fallback for Spring/framework code.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex) {
        if (ex.code().status() >= 500
                && ex.code() != ErrorCode.NOT_IMPLEMENTED
                && ex.code() != ErrorCode.UPSTREAM_ERROR
                && ex.code() != ErrorCode.UPSTREAM_UNAVAILABLE) {
            log.error("request failed: {}", ex.code(), ex);
        } else {
            log.warn("request rejected: {} - {}", ex.code(), ex.getMessage());
        }
        return respond(ex.code(), ex.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.warn("malformed request body: {}", ex.getMessage());
        return respond(ErrorCode.INVALID_REQUEST, "malformed request body");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException ex) {
        ErrorCode code = ErrorCode.fromStatus(ex.getStatusCode().value());
        String message = ex.getReason() == null ? code.name() : ex.getReason();
        return respond(code, message);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("unhandled exception", ex);
        return respond(ErrorCode.INTERNAL, "internal error");
    }

    private ResponseEntity<ApiError> respond(ErrorCode code, String message) {
        return ResponseEntity.status(HttpStatus.valueOf(code.status())).body(ApiError.of(code, message));
    }
}
