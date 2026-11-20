package com.agentcore.server.dto.kb;

/**
 * Create or re-point one evaluation label. {@code expectation=no_answer}
 * declares "this question has no answer in this KB" and must not carry a
 * passage target; {@code answer} (the default) resolves the passage and its
 * fingerprint on the server.
 */
public record KbEvalLabelCreateRequest(
        String kbId, String query, String expectation, String documentId, Integer seq) {}
