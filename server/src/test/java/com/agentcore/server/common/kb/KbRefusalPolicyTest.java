package com.agentcore.server.common.kb;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Threshold judgment is a pure function so every combination is cheap to pin. */
class KbRefusalPolicyTest {

    @Test
    void keepsResultsWhenNoThresholdIsConfigured() {
        var verdict = KbRefusalPolicy.evaluate(
                0.42, true, new KbScoreThresholds(null, null));

        assertThat(verdict.noAnswer()).isFalse();
        assertThat(verdict.maxScore()).isEqualTo(0.42);
        assertThat(verdict.threshold()).isNull();
        assertThat(verdict.source()).isNull();
    }

    @Test
    void refusesWhenTheTopScoreIsBelowTheRerankThreshold() {
        var verdict = KbRefusalPolicy.evaluate(
                0.042, true, new KbScoreThresholds(0.15, 0.30));

        assertThat(verdict.noAnswer()).isTrue();
        assertThat(verdict.maxScore()).isEqualTo(0.042);
        assertThat(verdict.threshold()).isEqualTo(0.15);
        assertThat(verdict.source()).isEqualTo("rerank");
    }

    @Test
    void keepsResultsWhenTheTopScoreClearsTheRerankThreshold() {
        var verdict = KbRefusalPolicy.evaluate(
                0.31, true, new KbScoreThresholds(0.15, 0.90));

        assertThat(verdict.noAnswer()).isFalse();
        assertThat(verdict.source()).isEqualTo("rerank");
        assertThat(verdict.threshold()).isEqualTo(0.15);
    }

    @Test
    void treatsAScoreEqualToTheThresholdAsAKeep() {
        var verdict = KbRefusalPolicy.evaluate(
                0.15, true, new KbScoreThresholds(0.15, null));

        assertThat(verdict.noAnswer()).isFalse();
    }

    @Test
    void usesTheVectorThresholdWhenRerankIsDisabled() {
        var verdict = KbRefusalPolicy.evaluate(
                0.20, false, new KbScoreThresholds(0.15, 0.55));

        assertThat(verdict.noAnswer()).isTrue();
        assertThat(verdict.source()).isEqualTo("vector");
        assertThat(verdict.threshold()).isEqualTo(0.55);
    }

    @Test
    void neverAppliesTheVectorThresholdToARerankedOrdering() {
        // A reranked score cannot be compared with a vector threshold: only the
        // rerank threshold (even when absent) governs that ordering.
        var verdict = KbRefusalPolicy.evaluate(
                0.02, true, new KbScoreThresholds(null, 0.90));

        assertThat(verdict.noAnswer()).isFalse();
        assertThat(verdict.threshold()).isNull();
        assertThat(verdict.source()).isNull();
    }

    @Test
    void neverJudgesAnEmptyResultSet() {
        var verdict = KbRefusalPolicy.evaluate(
                null, false, new KbScoreThresholds(0.15, 0.30));

        assertThat(verdict.noAnswer()).isFalse();
        assertThat(verdict.maxScore()).isNull();
    }
}
