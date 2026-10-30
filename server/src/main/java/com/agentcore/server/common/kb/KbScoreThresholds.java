package com.agentcore.server.common.kb;

/**
 * Per-user refusal thresholds (ADR-0029). Each value applies to its own score
 * scale: {@code rerank} to the reranker's scores, {@code vector} to Qdrant's
 * cosine similarity. {@code null} means "do not refuse".
 */
public record KbScoreThresholds(Double rerank, Double vector) {

    public static final KbScoreThresholds NONE = new KbScoreThresholds(null, null);
}
