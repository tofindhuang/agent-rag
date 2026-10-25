package com.agentcore.server.dto.kb;

import java.time.Instant;

/**
 * A label as shown in the management UI. {@code state=rotten} means the target
 * passage is gone or its text fingerprint changed after a rebuild.
 */
public record KbEvalLabelView(
        long id,
        String query,
        String documentId,
        int seq,
        String title,
        Integer page,
        String state,
        Instant createdAt) {

    public static final String STATE_OK = "ok";
    public static final String STATE_ROTTEN = "rotten";
}
