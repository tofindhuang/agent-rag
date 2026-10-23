package com.agentcore.server.common.kb;

/**
 * Infrastructure failure from a KB store (object storage or vector store).
 *
 * <p>Consumers distinguish retryable transport/rate-limit errors from permanent
 * ones (missing object, malformed response, dimension mismatch) when deciding
 * between retry tiers and the DLQ.
 */
public class KbStoreException extends RuntimeException {

    private final boolean retryable;
    private final boolean notFound;

    protected KbStoreException(String message, Throwable cause, boolean retryable, boolean notFound) {
        super(message, cause);
        this.retryable = retryable;
        this.notFound = notFound;
    }

    public boolean retryable() {
        return retryable;
    }

    public boolean notFound() {
        return notFound;
    }

    public static KbStoreException retryable(String message, Throwable cause) {
        return new KbStoreException(message, cause, true, false);
    }

    public static KbStoreException permanent(String message, Throwable cause) {
        return new KbStoreException(message, cause, false, false);
    }

    public static KbStoreException notFound(String message) {
        return new KbStoreException(message, null, false, true);
    }

    public static KbStoreException notFound(String message, Throwable cause) {
        return new KbStoreException(message, cause, false, true);
    }
}
