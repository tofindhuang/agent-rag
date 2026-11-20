package com.agentcore.server.dto.kb;

import java.util.List;

/** Debug query: the same candidate pool rendered in vector order and in the user's effective order. */
public record KbRetrievalQueryRequest(String kbId, String query) {}
