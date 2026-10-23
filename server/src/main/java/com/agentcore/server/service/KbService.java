package com.agentcore.server.service;

import com.agentcore.server.dto.kb.KbSearchRequest;
import com.agentcore.server.dto.kb.KbSearchResponse;

/**
 * Knowledge-base retrieval use cases. Controllers depend on this interface;
 * the implementation lives in {@code service.impl}.
 */
public interface KbService {

    KbSearchResponse search(KbSearchRequest request);
}
