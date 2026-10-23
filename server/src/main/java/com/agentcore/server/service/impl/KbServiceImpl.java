package com.agentcore.server.service.impl;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.common.identity.ToolIdentity;
import com.agentcore.server.dto.kb.KbSearchRequest;
import com.agentcore.server.dto.kb.KbSearchResponse;
import com.agentcore.server.service.KbService;
import org.springframework.stereotype.Service;

/**
 * Stub implementation; the RAG pipeline lands in M1 (FR-2).
 */
@Service
public class KbServiceImpl implements KbService {

    @Override
    public KbSearchResponse search(KbSearchRequest request, ToolIdentity identity) {
        throw new ApiException(ErrorCode.NOT_IMPLEMENTED,
                "RAG pipeline lands in M1 (requirements.md FR-2)");
    }
}
