package com.agentcore.server.dto.kb;

import com.agentcore.server.common.kb.KbEvalMetrics;
import java.util.List;

/** Full report of one run, returned by the run endpoint and the detail endpoint. */
public record KbEvalRunResponse(
        long runId,
        boolean complete,
        int labelCount,
        int noAnswerCount,
        int executedCount,
        int rottenCount,
        int skippedCount,
        String embeddingModel,
        int embeddingDim,
        String rerankModel,
        int durationMs,
        Double thresholdUsed,
        String thresholdSource,
        KbEvalMetrics metrics,
        List<KbEvalItemView> items,
        List<KbEvalIssueView> rotten,
        List<KbEvalIssueView> skipped) {}
