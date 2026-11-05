package com.agentcore.server.dto.admin;

import java.time.Instant;

/** One audit row (ADR-0033). Metadata only: no content, no secrets. */
public record AuditEventView(
        long id,
        Long actorUserId,
        String actorType,
        String action,
        String targetType,
        String targetId,
        String outcome,
        String ip,
        Instant createdAt,
        /** Admin view only; the self-service view omits it. */
        String detail) {}
