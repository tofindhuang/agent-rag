package com.agentcore.server.common.vector;

import java.util.List;

/**
 * Vector-store boundary (Qdrant, ADR-0005). Collections are per user
 * ({@code kb_passages_u{userId}}) and created lazily with a locked dimension.
 */
public interface VectorStore {

    String collectionName(long userId);

    /** All collections known to the store, including non-KB ones. */
    List<VectorCollection> collections();

    /**
     * Creates the user's collection on first use. An existing collection with a
     * different dimension raises {@link
     * com.agentcore.server.common.kb.KbDimensionMismatchException}.
     */
    void ensureCollection(long userId, int dimension);

    /** Drops and recreates the user's collection at a new dimension. */
    void recreateCollection(long userId, int dimension);

    /** Upserts points after ensuring the collection exists. */
    void upsert(long userId, int dimension, List<VectorPoint> points);

    /** Deletes every point of one document; idempotent. */
    void deleteByDocument(long userId, String documentId);

    /** Top-k similarity search restricted to the user; empty when no collection. */
    List<VectorHit> search(long userId, float[] query, int limit);
}
