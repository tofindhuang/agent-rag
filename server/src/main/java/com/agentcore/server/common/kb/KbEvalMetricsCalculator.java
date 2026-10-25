package com.agentcore.server.common.kb;

import java.util.List;

/**
 * Pure metric arithmetic (ADR-0022). Only executed, non-rotten observations
 * are passed in; the caller owns the filter and the "no valid labels" rule.
 */
public final class KbEvalMetricsCalculator {

    /** One executed label: final rank in top-5 and real rank in the candidate pool. */
    public record Observation(Integer rankFinal, Integer rankVector) {}

    private KbEvalMetricsCalculator() {}

    public static KbEvalMetrics compute(List<Observation> observations) {
        if (observations.isEmpty()) {
            throw new IllegalArgumentException("metrics need at least one observation");
        }
        int total = observations.size();
        int finalHits = 0;
        double finalReciprocalSum = 0;
        int poolHits = 0;
        int baselineHits = 0;
        double baselineReciprocalSum = 0;

        for (Observation observation : observations) {
            Integer finalRank = observation.rankFinal();
            if (finalRank != null) {
                finalHits++;
                finalReciprocalSum += 1.0 / finalRank;
            }
            Integer vectorRank = observation.rankVector();
            if (vectorRank != null) {
                poolHits++;
                if (vectorRank <= KbRetrievalCore.DEFAULT_TOP_K) {
                    baselineHits++;
                    baselineReciprocalSum += 1.0 / vectorRank;
                }
            }
        }
        return new KbEvalMetrics(
                (double) finalHits / total,
                finalReciprocalSum / total,
                (double) poolHits / total,
                (double) baselineHits / total,
                baselineReciprocalSum / total);
    }
}
