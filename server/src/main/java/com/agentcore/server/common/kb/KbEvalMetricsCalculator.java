package com.agentcore.server.common.kb;

import java.util.List;

/**
 * Pure metric arithmetic (ADR-0022, ADR-0029). Only executed, non-rotten
 * observations are passed in; the caller owns the filter and the "no valid
 * labels" rule.
 *
 * <p>No-answer labels are scored by whether retrieval refused them, so they
 * never enter the recall denominators and answer labels never enter the
 * refusal denominator.
 */
public final class KbEvalMetricsCalculator {

    /**
     * One executed label.
     *
     * @param noAnswer true for labels that declare "the knowledge base has no answer"
     * @param refused the run refused this query (only meaningful when {@code noAnswer})
     * @param rankFinal final rank of the target passage in top-k (answer labels only)
     * @param rankVector the target passage's rank in the candidate pool (answer labels only)
     */
    public record Observation(
            boolean noAnswer, boolean refused, Integer rankFinal, Integer rankVector) {}

    private KbEvalMetricsCalculator() {}

    public static KbEvalMetrics compute(List<Observation> observations) {
        if (observations.isEmpty()) {
            throw new IllegalArgumentException("metrics need at least one observation");
        }
        int answerTotal = 0;
        int finalHits = 0;
        double finalReciprocalSum = 0;
        int poolHits = 0;
        int baselineHits = 0;
        double baselineReciprocalSum = 0;
        int noAnswerTotal = 0;
        int refusals = 0;

        for (Observation observation : observations) {
            if (observation.noAnswer()) {
                noAnswerTotal++;
                if (observation.refused()) refusals++;
                continue;
            }
            answerTotal++;
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
                answerTotal == 0 ? null : (double) finalHits / answerTotal,
                answerTotal == 0 ? null : finalReciprocalSum / answerTotal,
                answerTotal == 0 ? null : (double) poolHits / answerTotal,
                answerTotal == 0 ? null : (double) baselineHits / answerTotal,
                answerTotal == 0 ? null : baselineReciprocalSum / answerTotal,
                noAnswerTotal == 0 ? null : (double) refusals / noAnswerTotal);
    }
}
