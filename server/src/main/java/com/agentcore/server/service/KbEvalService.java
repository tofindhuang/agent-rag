package com.agentcore.server.service;

import com.agentcore.server.dto.kb.KbEvalRunListResponse;
import com.agentcore.server.dto.kb.KbEvalRunResponse;

/** Synchronous evaluation runs and their per-KB history (ADR-0022). */
public interface KbEvalService {

    KbEvalRunResponse run(long userId, String kbId);

    KbEvalRunListResponse listRuns(long userId, String kbId, int limit);

    KbEvalRunResponse getRun(long userId, long runId);
}
