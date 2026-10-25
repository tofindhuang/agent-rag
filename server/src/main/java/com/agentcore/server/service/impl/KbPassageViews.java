package com.agentcore.server.service.impl;

import com.agentcore.server.common.kb.KbRetrievalCore.RankedHit;
import com.agentcore.server.common.vector.VectorHit;
import com.agentcore.server.domain.KbKnowledgeBase;
import com.agentcore.server.dto.kb.KbSearchResponse;

/** One citation-shaped mapping shared by the tool callback and the debug query. */
final class KbPassageViews {

    private KbPassageViews() {}

    static KbSearchResponse.Passage toPassage(
            VectorHit hit, double score, boolean reranked, KbKnowledgeBase kb) {
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

    static KbSearchResponse.Passage toPassage(RankedHit ranked, KbKnowledgeBase kb) {
        return toPassage(ranked.hit(), ranked.score(), ranked.reranked(), kb);
    }
}
