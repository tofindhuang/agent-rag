package com.agentcore.server.controller;

import com.agentcore.server.common.auth.AuthenticatedUser;
import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.dto.kb.KbEvalLabelCreateRequest;
import com.agentcore.server.dto.kb.KbEvalLabelListResponse;
import com.agentcore.server.dto.kb.KbEvalLabelView;
import com.agentcore.server.dto.kb.KbEvalRunListResponse;
import com.agentcore.server.dto.kb.KbEvalRunRequest;
import com.agentcore.server.dto.kb.KbEvalRunResponse;
import com.agentcore.server.dto.kb.KbRetrievalQueryRequest;
import com.agentcore.server.dto.kb.KbRetrievalQueryResponse;
import com.agentcore.server.service.KbEvalLabelService;
import com.agentcore.server.service.KbEvalService;
import com.agentcore.server.service.KbRetrievalService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Knowledge-base retrieval debugging for the management UI (ADR-0022). */
@RestController
@RequestMapping("/api/kb/retrieval")
public class KbRetrievalController {

    private static final int MAX_QUERY_CHARS = 512;

    private final KbRetrievalService retrievalService;
    private final KbEvalLabelService labelService;
    private final KbEvalService evalService;

    public KbRetrievalController(
            KbRetrievalService retrievalService,
            KbEvalLabelService labelService,
            KbEvalService evalService) {
        this.retrievalService = retrievalService;
        this.labelService = labelService;
        this.evalService = evalService;
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

    @GetMapping("/labels")
    public KbEvalLabelListResponse labels(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam("kbId") String kbId) {
        return labelService.list(user.userId(), kbId);
    }

    @PostMapping("/labels")
    public KbEvalLabelView createLabel(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody KbEvalLabelCreateRequest request) {
        return labelService.create(user.userId(), request);
    }

    @DeleteMapping("/labels/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLabel(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") long id) {
        labelService.delete(user.userId(), id);
    }

    @PostMapping("/eval/run")
    public KbEvalRunResponse runEvaluation(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody KbEvalRunRequest request) {
        if (request.kbId() == null || request.kbId().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "kbId is required");
        }
        return evalService.run(user.userId(), request.kbId());
    }

    @GetMapping("/eval/runs")
    public KbEvalRunListResponse runs(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam("kbId") String kbId,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        if (kbId == null || kbId.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "kbId is required");
        }
        return evalService.listRuns(user.userId(), kbId, limit);
    }

    @GetMapping("/eval/runs/{runId}")
    public KbEvalRunResponse runDetail(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable("runId") long runId) {
        return evalService.getRun(user.userId(), runId);
    }
}
