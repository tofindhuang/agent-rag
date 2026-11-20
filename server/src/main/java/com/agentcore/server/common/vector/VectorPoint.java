package com.agentcore.server.common.vector;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** One Qdrant point: deterministic id + vector + passage payload. */
public record VectorPoint(String id, float[] vector, VectorPayload payload) {

    public VectorPoint {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(vector, "vector");
        Objects.requireNonNull(payload, "payload");
    }

    /**
     * Stable point id for {@code (documentId, seq)} so re-ingestion overwrites
     * the same point instead of creating duplicates.
     */
    public static String pointId(String documentId, int seq) {
        return UUID.nameUUIDFromBytes(
                        (documentId + ":" + seq).getBytes(StandardCharsets.UTF_8))
                .toString();
    }
}
