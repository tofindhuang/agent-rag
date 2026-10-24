package com.agentcore.server.common.kb;

/**
 * Classified OCR sidecar failure. Retryable failures re-enter the ingestion
 * ladder; permanent ones fail the document immediately.
 */
public class KbOcrException extends RuntimeException {

    private final String errorCode;
    private final boolean retryable;

    private KbOcrException(String errorCode, String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public static KbOcrException permanent(String errorCode, String message) {
        return new KbOcrException(errorCode, message, null, false);
    }

    public static KbOcrException permanent(String errorCode, String message, Throwable cause) {
        return new KbOcrException(errorCode, message, cause, false);
    }

    public static KbOcrException retryable(String errorCode, String message, Throwable cause) {
        return new KbOcrException(errorCode, message, cause, true);
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }
}
