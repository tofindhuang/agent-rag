package com.agentcore.server.dto.kb;

import com.agentcore.server.common.kb.KbEvalMetrics;
import java.time.Instant;

/** History row; the UI computes deltas against the previous complete run. */
public record KbEvalRunSummary(
        long runId,
        Instant createdAt,
        boolean complete,
        int labelCount,
        int executedCount,
        int rottenCount,
        int skippedCount,
        String embeddingModel,
        int embeddingDim,
        String rerankModel,
        int durationMs,
        KbEvalMetrics metrics) {}
