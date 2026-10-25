package com.agentcore.server.service;

import com.agentcore.server.dto.kb.KbRetrievalQueryRequest;
import com.agentcore.server.dto.kb.KbRetrievalQueryResponse;

/** Retrieval debugging for the knowledge-base UI (ADR-0022). */
public interface KbRetrievalService {

    KbRetrievalQueryResponse query(long userId, KbRetrievalQueryRequest request);
}
