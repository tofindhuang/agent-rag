package com.agentcore.server.dto.kb;

import java.time.Instant;

/**
 * A label as shown in the management UI. {@code state=rotten} means the target
 * passage is gone or its text fingerprint changed after a rebuild; no-answer
 * labels have no target and therefore stay {@code ok}.
 */
public record KbEvalLabelView(
        long id,
        String query,
        String expectation,
        String documentId,
        Integer seq,
        String title,
        Integer page,
        String state,
        Instant createdAt) {

    public static final String STATE_OK = "ok";
    public static final String STATE_ROTTEN = "rotten";
}
