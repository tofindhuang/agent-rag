package com.agentcore.server.dto.kb;

import java.util.List;

/** Label list for one KB with the per-KB cap so the UI can render {@code count / limit}. */
public record KbEvalLabelListResponse(List<KbEvalLabelView> items, int total, int limit) {}
