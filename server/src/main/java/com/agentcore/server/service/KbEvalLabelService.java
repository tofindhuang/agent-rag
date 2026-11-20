package com.agentcore.server.service;

import com.agentcore.server.dto.kb.KbEvalLabelCreateRequest;
import com.agentcore.server.dto.kb.KbEvalLabelListResponse;
import com.agentcore.server.dto.kb.KbEvalLabelView;

/** Per-KB evaluation labels (ADR-0022). */
public interface KbEvalLabelService {

    KbEvalLabelListResponse list(long userId, String kbId);

    KbEvalLabelView create(long userId, KbEvalLabelCreateRequest request);

    void delete(long userId, long labelId);
}
