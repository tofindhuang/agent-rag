package com.agentcore.server.common.storage;

import java.nio.file.Path;

/**
 * Object-storage boundary for KB originals (ADR-0013). Implementations talk
 * the S3 API; services never see SDK types.
 */
public interface ObjectStore {

    /** Builds the canonical key, see {@link ObjectKey#of}. */
    String key(long userId, String documentId, String sha256, String filename);

    /**
     * Uploads a file with content type and sha256 metadata. Creates the bucket
     * on first use.
     */
    void put(String key, Path source, String contentType, String sha256);

    /** Opens the object; missing keys raise a non-retryable store error. */
    StoredObject get(String key);

    /** Idempotent delete; a missing object or bucket is not an error. */
    void delete(String key);

    boolean exists(String key);
}
