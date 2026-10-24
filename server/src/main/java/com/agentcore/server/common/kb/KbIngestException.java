package com.agentcore.server.common.kb;

/**
 * A classified ingestion failure. Retryable failures enter the 5s/30s/2m
 * ladder; permanent failures (bad format, dimension mismatch, auth) mark the
 * document failed immediately.
 */
public class KbIngestException extends RuntimeException {

    public static final String UNSUPPORTED_FORMAT = "UNSUPPORTED_FORMAT";
    public static final String CORRUPT_DOCUMENT = "CORRUPT_DOCUMENT";
    public static final String DOCUMENT_TOO_LARGE = "DOCUMENT_TOO_LARGE";
    public static final String DOCUMENT_EMPTY = "DOCUMENT_EMPTY";
    public static final String EMBEDDING_NOT_CONFIGURED = "EMBEDDING_NOT_CONFIGURED";
    public static final String EMBEDDING_REJECTED = "EMBEDDING_REJECTED";
    public static final String EMBEDDING_UNAVAILABLE = "EMBEDDING_UNAVAILABLE";
    public static final String EMBEDDING_DIMENSION_MISMATCH = "EMBEDDING_DIMENSION_MISMATCH";
    public static final String EMBEDDING_RESPONSE_INVALID = "EMBEDDING_RESPONSE_INVALID";
    public static final String STORAGE_UNAVAILABLE = "STORAGE_UNAVAILABLE";
    public static final String OCR_PAGE_LIMIT = "OCR_PAGE_LIMIT";
    public static final String OCR_UNAVAILABLE = "OCR_UNAVAILABLE";
    public static final String OCR_TIMEOUT = "OCR_TIMEOUT";
    public static final String OCR_INVALID_IMAGE = "OCR_INVALID_IMAGE";
    public static final String INGEST_FAILED = "INGEST_FAILED";

    private final String errorCode;
    private final boolean retryable;

    private KbIngestException(String errorCode, String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public static KbIngestException permanent(String errorCode, String message) {
        return new KbIngestException(errorCode, message, null, false);
    }

    public static KbIngestException permanent(String errorCode, String message, Throwable cause) {
        return new KbIngestException(errorCode, message, cause, false);
    }

    public static KbIngestException retryable(String errorCode, String message, Throwable cause) {
        return new KbIngestException(errorCode, message, cause, true);
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }
}
