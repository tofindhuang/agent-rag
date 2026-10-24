package com.agentcore.server.dto.kb;

import java.util.List;

/** Evidence that the saved settings are actually used by retrieval. */
public record KbRetrievalTestResponse(
        String query,
        String embeddingModel,
        int dimension,
        long embeddingLatencyMs,
        int candidates,
        int hits,
        boolean reranked,
        String rerankModel,
        Long rerankLatencyMs,
        List<KbSearchResponse.Passage> passages) {}
