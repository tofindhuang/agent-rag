package com.agentcore.server.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
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
                && ex.code() != ErrorCode.UPSTREAM_UNAVAILABLE
                && ex.code() != ErrorCode.SANDBOX_UNAVAILABLE) {
            log.error("request failed: {}", ex.code(), ex);
        } else {
            log.warn("request rejected: {} - {}", ex.code(), ex.getMessage());
        }
        return respond(ex.code(), ex.getMessage(), ex.retryAfterSeconds());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.warn("malformed request body: {}", ex.getMessage());
        return respond(ErrorCode.INVALID_REQUEST, "malformed request body");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("invalid request parameter {}: {}", ex.getName(), ex.getMessage());
        return respond(ErrorCode.INVALID_REQUEST, "invalid parameter: " + ex.getName());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxUpload(MaxUploadSizeExceededException ex) {
        log.warn("upload rejected: {}", ex.getMessage());
        return respond(ErrorCode.KB_FILE_TOO_LARGE, "file exceeds the 20 MB limit");
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiError> handleMultipart(MultipartException ex) {
        log.warn("malformed multipart request: {}", ex.getMessage());
        return respond(ErrorCode.INVALID_REQUEST, "malformed multipart request");
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
        return respond(code, message, null);
    }

    private ResponseEntity<ApiError> respond(
            ErrorCode code, String message, Long retryAfterSeconds) {
        // SSE prompt requests send `accept: text/event-stream`; a preset
        // content type skips content negotiation so error envelopes stay JSON.
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.valueOf(code.status()))
                .contentType(MediaType.APPLICATION_JSON);
        if (retryAfterSeconds != null) {
            builder.header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        }
        return builder.body(ApiError.of(code, message));
    }
}
