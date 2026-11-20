package com.agentcore.server.common.kb;

/**
 * A classified rerank failure. The settings probe turns it into a readable
 * error; retrieval (ticket 05) treats retryable failures as {@code
 * KB_UNAVAILABLE} instead of silently degrading to vector scores.
 */
public class KbRerankException extends RuntimeException {

    public static final String RERANK_NOT_CONFIGURED = "RERANK_NOT_CONFIGURED";
    public static final String RERANK_REJECTED = "RERANK_REJECTED";
    public static final String RERANK_UNAVAILABLE = "RERANK_UNAVAILABLE";
    public static final String RERANK_RESPONSE_INVALID = "RERANK_RESPONSE_INVALID";

    private final String errorCode;
    private final boolean retryable;

    private KbRerankException(
            String errorCode, String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public static KbRerankException permanent(String errorCode, String message) {
        return new KbRerankException(errorCode, message, null, false);
    }

    public static KbRerankException permanent(
            String errorCode, String message, Throwable cause) {
        return new KbRerankException(errorCode, message, cause, false);
    }

    public static KbRerankException retryable(String errorCode, String message, Throwable cause) {
        return new KbRerankException(errorCode, message, cause, true);
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }
}
