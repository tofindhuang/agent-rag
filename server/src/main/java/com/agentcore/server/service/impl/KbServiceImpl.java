package com.agentcore.server.service.impl;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.common.identity.ToolIdentity;
import com.agentcore.server.common.kb.KbIndexGuard;
import com.agentcore.server.common.kb.KbRetrievalCore;
import com.agentcore.server.dao.KbKnowledgeBaseDao;
import com.agentcore.server.dao.KbSessionKnowledgeBaseDao;
import com.agentcore.server.domain.KbKnowledgeBase;
import com.agentcore.server.dto.kb.KbSearchRequest;
import com.agentcore.server.dto.kb.KbSearchResponse;
import com.agentcore.server.service.KbService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Retrieval use case for the {@code kb_search} tool callback (FR-2). Session
 * binding and index guards live here; the shared pipeline lives in
 * {@link KbRetrievalCore}.
 */
@Service
public class KbServiceImpl implements KbService {

    private static final Logger log = LoggerFactory.getLogger(KbServiceImpl.class);

    private final KbIndexGuard indexGuard;
    private final KbRetrievalCore retrievalCore;
    private final KbSessionKnowledgeBaseDao sessionKbDao;
    private final KbKnowledgeBaseDao knowledgeBaseDao;

    public KbServiceImpl(
            KbIndexGuard indexGuard,
            KbRetrievalCore retrievalCore,
            KbSessionKnowledgeBaseDao sessionKbDao,
            KbKnowledgeBaseDao knowledgeBaseDao) {
        this.indexGuard = indexGuard;
        this.retrievalCore = retrievalCore;
        this.sessionKbDao = sessionKbDao;
        this.knowledgeBaseDao = knowledgeBaseDao;
    }

    @Override
    public KbSearchResponse search(KbSearchRequest request, ToolIdentity identity) {
        long userId = parseUserId(identity);
        indexGuard.requireIndexUsable(userId);

        String kbId = sessionKbDao.findKbId(identity.sessionId());
        KbKnowledgeBase kb = kbId == null ? null : knowledgeBaseDao.findOwned(kbId, userId);
        if (kb == null) {
            return new KbSearchResponse(List.of());
        }

        var embedding = retrievalCore.resolveEmbedding(userId);
        indexGuard.requireFingerprint(userId, embedding);

        int topK = KbRetrievalCore.normalizeTopK(request.topK());
        KbRetrievalCore.Outcome outcome =
                retrievalCore.retrieve(userId, kbId, embedding, request.query(), topK);
        if (outcome.noAnswer()) {
            log.info("kb_search refused for session {} kb {}: maxScore={} threshold={} source={}",
                    identity.sessionId(), kbId, outcome.maxScore(), outcome.threshold(),
                    outcome.thresholdSource());
        }
        List<KbSearchResponse.Passage> passages = outcome.ranked().stream()
                .map((hit) -> KbPassageViews.toPassage(hit, kb))
                .toList();
        // The tool contract stays byte-identical on the happy path: the score
        // numbers are only meaningful (and only sent) when refusing.
        return new KbSearchResponse(
                passages,
                outcome.noAnswer(),
                outcome.noAnswer() ? outcome.maxScore() : null,
                outcome.noAnswer() ? outcome.threshold() : null);
    }

    private static long parseUserId(ToolIdentity identity) {
        try {
            return Long.parseLong(identity.userId());
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "X-User-Id must be numeric");
        }
    }
}
