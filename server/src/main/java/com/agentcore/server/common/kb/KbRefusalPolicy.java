package com.agentcore.server.common.kb;

/**
 * Decides whether a retrieval result should be reported as "no answer"
 * (ADR-0029). The judgment is deliberately a pure function: the threshold must
 * be reproducible in evaluation runs and explainable in the debug panel.
 *
 * <p>Only the threshold that belongs to the ordering's own score scale is
 * applied — a reranked score is never compared with a vector threshold.
 */
public final class KbRefusalPolicy {

    public static final String SOURCE_RERANK = "rerank";
    public static final String SOURCE_VECTOR = "vector";

    /**
     * @param maxScore highest score of the final ordering, or {@code null} when it is empty
     */
    public record Verdict(boolean noAnswer, Double maxScore, Double threshold, String source) {}

    private KbRefusalPolicy() {}

    public static Verdict evaluate(
            Double maxScore, boolean reranked, KbScoreThresholds thresholds) {
        Double threshold = reranked ? thresholds.rerank() : thresholds.vector();
        if (threshold == null || maxScore == null) {
            return new Verdict(false, maxScore, null, null);
        }
        return new Verdict(
                maxScore < threshold,
                maxScore,
                threshold,
                reranked ? SOURCE_RERANK : SOURCE_VECTOR);
    }
}
