package com.agentcore.server.common.kb;

/**
 * The five numbers of one evaluation run (ADR-0022). {@code baseline*} is the
 * same candidate pool ordered by vector similarity (rerank disabled); the
 * difference between the two pairs is rerank's contribution.
 */
public record KbEvalMetrics(
        double recall5,
        double mrr5,
        double poolRecall10,
        double baselineRecall5,
        double baselineMrr5) {}
