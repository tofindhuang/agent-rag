package com.agentcore.server.common.kb;

/**
 * The numbers of one evaluation run (ADR-0022, ADR-0029). {@code baseline*} is
 * the same candidate pool ordered by vector similarity (rerank disabled); the
 * difference between the two pairs is rerank's contribution.
 *
 * <p>Recall-family metrics are {@code null} when the run executed no answer
 * labels; {@code refusalAccuracy} is {@code null} when it executed no
 * no-answer labels. A missing sample is not a zero score.
 */
public record KbEvalMetrics(
        Double recall5,
        Double mrr5,
        Double poolRecall10,
        Double baselineRecall5,
        Double baselineMrr5,
        Double refusalAccuracy) {}
