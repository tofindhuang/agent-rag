package com.agentcore.server.controller;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.common.identity.ToolIdentity;
import com.agentcore.server.dto.kb.KbSearchRequest;
import com.agentcore.server.dto.kb.KbSearchResponse;
import com.agentcore.server.service.KbService;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stub for the kb_search tool callback (requirements.md FR-2).
 * The RAG retrieval pipeline (vector store + rerank) lands in M1.
 */
@RestController
@RequestMapping("/api/kb")
public class KbController {

    private final KbService kbService;

    public KbController(KbService kbService) {
        this.kbService = kbService;
    }

    @PostMapping("/search")
    public KbSearchResponse search(
            @RequestHeader HttpHeaders headers,
            @RequestBody KbSearchRequest request) {
        ToolIdentity identity = ToolIdentity.from(headers);
        if (request.query() == null || request.query().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "query is required");
        }
        return kbService.search(request, identity);
    }
}
