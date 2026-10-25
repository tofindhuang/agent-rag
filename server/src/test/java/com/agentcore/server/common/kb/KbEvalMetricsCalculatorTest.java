package com.agentcore.server.common.kb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

/** Metric definitions are the contract of a run report; keep them independently recomputable. */
class KbEvalMetricsCalculatorTest {

    private static final Offset<Double> EXACT = Offset.offset(0.0001);

    @Test
    void splitsEmbeddingCeilingFromFinalOrdering() {
        KbEvalMetrics metrics = KbEvalMetricsCalculator.compute(List.of(
                new KbEvalMetricsCalculator.Observation(1, 1),
                new KbEvalMetricsCalculator.Observation(3, 2),
                new KbEvalMetricsCalculator.Observation(null, 6)));

        assertThat(metrics.recall5()).isCloseTo(2.0 / 3.0, EXACT);
        assertThat(metrics.mrr5()).isCloseTo(4.0 / 9.0, EXACT);
        assertThat(metrics.poolRecall10()).isCloseTo(1.0, EXACT);
        assertThat(metrics.baselineRecall5()).isCloseTo(2.0 / 3.0, EXACT);
        assertThat(metrics.baselineMrr5()).isCloseTo(0.5, EXACT);
    }

    @Test
    void isIdenticalWhenRerankIsDisabled() {
        KbEvalMetrics metrics = KbEvalMetricsCalculator.compute(List.of(
                new KbEvalMetricsCalculator.Observation(1, 1),
                new KbEvalMetricsCalculator.Observation(2, 2),
                new KbEvalMetricsCalculator.Observation(null, 8)));

        assertThat(metrics.recall5()).isEqualTo(metrics.baselineRecall5());
        assertThat(metrics.mrr5()).isEqualTo(metrics.baselineMrr5());
        assertThat(metrics.poolRecall10()).isCloseTo(1.0, EXACT);
    }

    @Test
    void countsOnlyObservationsThatWereExecuted() {
        KbEvalMetrics metrics = KbEvalMetricsCalculator.compute(List.of(
                new KbEvalMetricsCalculator.Observation(null, null)));

        assertThat(metrics.recall5()).isZero();
        assertThat(metrics.mrr5()).isZero();
        assertThat(metrics.poolRecall10()).isZero();
        assertThat(metrics.baselineRecall5()).isZero();
        assertThat(metrics.baselineMrr5()).isZero();
    }
}
