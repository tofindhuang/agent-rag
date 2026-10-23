package com.agentcore.server.common.storage;

import java.util.Locale;

/**
 * Object-key rule (ADR-0013): {@code {userId}/{documentId}/{sha256}.{ext}}.
 * The digest makes retries and re-uploads idempotent; the extension keeps the
 * original format discoverable for re-ingestion.
 */
public final class ObjectKey {

    private ObjectKey() {}

    public static String of(long userId, String documentId, String sha256, String filename) {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId is required");
        }
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be 64 lowercase hex characters");
        }
        return userId + "/" + documentId + "/" + sha256 + "." + extension(filename);
    }

    /** Lowercase alphanumeric extension, {@code bin} when absent or unsafe. */
    public static String extension(String filename) {
        if (filename == null) {
            return "bin";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "bin";
        }
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,10}") ? ext : "bin";
    }
}
