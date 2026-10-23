package com.agentcore.server.service.impl;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.common.identity.ToolIdentity;
import com.agentcore.server.common.kb.KbAvailability;
import com.agentcore.server.dto.kb.KbSearchRequest;
import com.agentcore.server.dto.kb.KbSearchResponse;
import com.agentcore.server.service.KbService;
import org.springframework.stereotype.Service;

/**
 * Stub implementation; the RAG pipeline lands in M1 (FR-2). The enabled gate is
 * already in place so {@code KB_ENABLED=false} has a stable error contract.
 */
@Service
public class KbServiceImpl implements KbService {

    private final KbAvailability availability;

    public KbServiceImpl(KbAvailability availability) {
        this.availability = availability;
    }

    @Override
    public KbSearchResponse search(KbSearchRequest request, ToolIdentity identity) {
        availability.requireEnabled();
        throw new ApiException(ErrorCode.NOT_IMPLEMENTED,
                "RAG pipeline lands in M1 (requirements.md FR-2)");
    }
}
