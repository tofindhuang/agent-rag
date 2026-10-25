package com.agentcore.server.controller;

import com.agentcore.server.common.auth.AuthenticatedUser;
import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.dto.kb.KbRetrievalQueryRequest;
import com.agentcore.server.dto.kb.KbRetrievalQueryResponse;
import com.agentcore.server.service.KbRetrievalService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Knowledge-base retrieval debugging for the management UI (ADR-0022). */
@RestController
@RequestMapping("/api/kb/retrieval")
public class KbRetrievalController {

    private static final int MAX_QUERY_CHARS = 512;

    private final KbRetrievalService retrievalService;

    public KbRetrievalController(KbRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @PostMapping("/query")
    public KbRetrievalQueryResponse query(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody KbRetrievalQueryRequest request) {
        if (request.kbId() == null || request.kbId().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "kbId is required");
        }
        if (request.query() == null || request.query().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "query is required");
        }
        if (request.query().length() > MAX_QUERY_CHARS) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "query must be at most " + MAX_QUERY_CHARS + " characters");
        }
        return retrievalService.query(user.userId(), request);
    }
}
