package com.agentcore.server.common.kb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

/** Metric definitions are the contract of a run report; keep them independently recomputable. */
class KbEvalMetricsCalculatorTest {

    private static final Offset<Double> EXACT = Offset.offset(0.0001);

    private static KbEvalMetricsCalculator.Observation answer(
            Integer rankFinal, Integer rankVector) {
        return new KbEvalMetricsCalculator.Observation(false, false, rankFinal, rankVector);
    }

    private static KbEvalMetricsCalculator.Observation noAnswer(boolean refused) {
        return new KbEvalMetricsCalculator.Observation(true, refused, null, null);
    }

    @Test
    void splitsEmbeddingCeilingFromFinalOrdering() {
        KbEvalMetrics metrics = KbEvalMetricsCalculator.compute(List.of(
                answer(1, 1),
                answer(3, 2),
                answer(null, 6)));

        assertThat(metrics.recall5()).isCloseTo(2.0 / 3.0, EXACT);
        assertThat(metrics.mrr5()).isCloseTo(4.0 / 9.0, EXACT);
        assertThat(metrics.poolRecall10()).isCloseTo(1.0, EXACT);
        assertThat(metrics.baselineRecall5()).isCloseTo(2.0 / 3.0, EXACT);
        assertThat(metrics.baselineMrr5()).isCloseTo(0.5, EXACT);
        assertThat(metrics.refusalAccuracy()).isNull();
    }

    @Test
    void isIdenticalWhenRerankIsDisabled() {
        KbEvalMetrics metrics = KbEvalMetricsCalculator.compute(List.of(
                answer(1, 1),
                answer(2, 2),
                answer(null, 8)));

        assertThat(metrics.recall5()).isEqualTo(metrics.baselineRecall5());
        assertThat(metrics.mrr5()).isEqualTo(metrics.baselineMrr5());
        assertThat(metrics.poolRecall10()).isCloseTo(1.0, EXACT);
    }

    @Test
    void countsOnlyObservationsThatWereExecuted() {
        KbEvalMetrics metrics = KbEvalMetricsCalculator.compute(List.of(answer(null, null)));

        assertThat(metrics.recall5()).isZero();
        assertThat(metrics.mrr5()).isZero();
        assertThat(metrics.poolRecall10()).isZero();
        assertThat(metrics.baselineRecall5()).isZero();
        assertThat(metrics.baselineMrr5()).isZero();
    }

    @Test
    void keepsNoAnswerSamplesOutOfTheRecallDenominator() {
        KbEvalMetrics metrics = KbEvalMetricsCalculator.compute(List.of(
                answer(1, 1),
                noAnswer(true),
                noAnswer(false)));

        assertThat(metrics.recall5()).isEqualTo(1.0);
        assertThat(metrics.mrr5()).isEqualTo(1.0);
        assertThat(metrics.refusalAccuracy()).isCloseTo(0.5, EXACT);
    }

    @Test
    void reportsNullRecallWhenOnlyNoAnswerSamplesRan() {
        KbEvalMetrics metrics = KbEvalMetricsCalculator.compute(List.of(noAnswer(true)));

        assertThat(metrics.recall5()).isNull();
        assertThat(metrics.mrr5()).isNull();
        assertThat(metrics.poolRecall10()).isNull();
        assertThat(metrics.baselineRecall5()).isNull();
        assertThat(metrics.baselineMrr5()).isNull();
        assertThat(metrics.refusalAccuracy()).isEqualTo(1.0);
    }
}
