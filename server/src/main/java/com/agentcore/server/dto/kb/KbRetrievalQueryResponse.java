package com.agentcore.server.dto.kb;

import java.util.List;

/**
 * Two orderings of one candidate pool: {@code vector} is the pure vector
 * prefix (the embedding ceiling), {@code current} is the user's effective
 * configuration (possibly reranked).
 */
public record KbRetrievalQueryResponse(
        String query,
        boolean reranked,
        String rerankModel,
        List<KbSearchResponse.Passage> current,
        List<KbSearchResponse.Passage> vector,
        boolean noAnswer,
        Double maxScore,
        Double threshold,
        String thresholdSource) {}
