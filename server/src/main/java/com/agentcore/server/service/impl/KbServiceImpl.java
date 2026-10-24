package com.agentcore.server.service.impl;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.common.identity.ToolIdentity;
import com.agentcore.server.common.kb.KbAvailability;
import com.agentcore.server.dao.KbDocumentDao;
import com.agentcore.server.dto.kb.KbSearchRequest;
import com.agentcore.server.dto.kb.KbSearchResponse;
import com.agentcore.server.service.KbService;
import org.springframework.stereotype.Service;

/**
 * Retrieval lands in ticket 05. This stub already enforces the two preconditions
 * retrieval must not skip: the KB gate and the stale-index guard set by a
 * model/dimension change (ticket 04).
 */
@Service
public class KbServiceImpl implements KbService {

    private final KbAvailability availability;
    private final KbDocumentDao documentDao;

    public KbServiceImpl(KbAvailability availability, KbDocumentDao documentDao) {
        this.availability = availability;
        this.documentDao = documentDao;
    }

    @Override
    public KbSearchResponse search(KbSearchRequest request, ToolIdentity identity) {
        availability.requireEnabled();
        long userId;
        try {
            userId = Long.parseLong(identity.userId());
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "X-User-Id must be numeric");
        }
        if (documentDao.countStaleByUser(userId) > 0) {
            throw new ApiException(ErrorCode.KB_INDEX_STALE,
                    "the embedding model or dimension changed; rebuild the knowledge base"
                            + " with reingest-all before searching");
        }
        throw new ApiException(ErrorCode.NOT_IMPLEMENTED,
                "RAG pipeline lands in M1 (requirements.md FR-2)");
    }
}
