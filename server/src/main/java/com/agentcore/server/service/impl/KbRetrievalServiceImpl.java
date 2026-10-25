package com.agentcore.server.service.impl;

import com.agentcore.server.common.kb.KbIndexGuard;
import com.agentcore.server.common.kb.KbRetrievalCore;
import com.agentcore.server.dto.kb.KbRetrievalQueryRequest;
import com.agentcore.server.dto.kb.KbRetrievalQueryResponse;
import com.agentcore.server.dto.kb.KbSearchResponse;
import com.agentcore.server.domain.KbKnowledgeBase;
import com.agentcore.server.service.KbKnowledgeBaseService;
import com.agentcore.server.service.KbRetrievalService;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Debug query: resolve the KB and guards, delegate the pipeline, then shape
 * the same pool into two orderings. It never writes chat state or citations.
 */
@Service
public class KbRetrievalServiceImpl implements KbRetrievalService {

    private static final int TOP_K = KbRetrievalCore.DEFAULT_TOP_K;

    private final KbIndexGuard indexGuard;
    private final KbRetrievalCore retrievalCore;
    private final KbKnowledgeBaseService knowledgeBaseService;

    public KbRetrievalServiceImpl(
            KbIndexGuard indexGuard,
            KbRetrievalCore retrievalCore,
            KbKnowledgeBaseService knowledgeBaseService) {
        this.indexGuard = indexGuard;
        this.retrievalCore = retrievalCore;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @Override
    public KbRetrievalQueryResponse query(long userId, KbRetrievalQueryRequest request) {
        indexGuard.requireIndexUsable(userId);
        KbKnowledgeBase kb = knowledgeBaseService.requireOwned(userId, request.kbId());
        var embedding = retrievalCore.resolveEmbedding(userId);
        indexGuard.requireFingerprint(userId, embedding);

        KbRetrievalCore.Outcome outcome =
                retrievalCore.retrieve(userId, kb.getId(), embedding, request.query(), TOP_K);
        List<KbSearchResponse.Passage> current = outcome.ranked().stream()
                .map((ranked) -> toPassage(ranked.hit(), ranked.score(), ranked.reranked(), kb))
                .toList();
        List<KbSearchResponse.Passage> vector = outcome.pool().stream()
                .limit(TOP_K)
                .map((hit) -> toPassage(hit, hit.score(), false, kb))
                .toList();
        return new KbRetrievalQueryResponse(
                request.query(), outcome.reranked(), outcome.rerankModel(), current, vector);
    }

    private static KbSearchResponse.Passage toPassage(
            com.agentcore.server.common.vector.VectorHit hit,
            double score,
            boolean reranked,
            KbKnowledgeBase kb) {
        return new KbSearchResponse.Passage(
                hit.id(),
                hit.payload().documentId(),
                hit.payload().seq(),
                hit.payload().page(),
                hit.payload().title(),
                hit.payload().text(),
                score,
                reranked,
                kb.getId(),
                kb.getName());
    }
}
