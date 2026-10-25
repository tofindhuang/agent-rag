package com.agentcore.server.dto.kb;

import java.util.List;

/** Newest-first history for one KB. */
public record KbEvalRunListResponse(List<KbEvalRunSummary> items) {}
