package com.agentcore.server.dto.kb;

/** Create or re-point one evaluation label; the server resolves the passage and its fingerprint. */
public record KbEvalLabelCreateRequest(String kbId, String query, String documentId, Integer seq) {}
