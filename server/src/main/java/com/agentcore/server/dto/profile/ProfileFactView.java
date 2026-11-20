package com.agentcore.server.dto.profile;

import java.time.Instant;

/** User-facing profile fact; never exposes user_id. */
public record ProfileFactView(
        String id,
        String category,
        String factKey,
        String factValue,
        String factTitle,
        String factText,
        String collectionId,
        String status,
        String supersedesId,
        String sourceSessionId,
        Instant createdAt,
        Instant updatedAt) {}
