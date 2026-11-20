package com.agentcore.server.dto.admin;

import com.agentcore.server.domain.AuditEvent;
import java.time.Instant;
import java.time.ZoneOffset;

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
        String detail) {

    /** Shared mapping so the admin and self-service views cannot drift apart. */
    public static AuditEventView of(AuditEvent event, boolean includeDetail) {
        return new AuditEventView(
                event.getId(),
                event.getActorUserId(),
                event.getActorType(),
                event.getAction(),
                event.getTargetType(),
                event.getTargetId(),
                event.getOutcome(),
                event.getIp(),
                event.getCreatedAt() == null ? null : event.getCreatedAt().toInstant(ZoneOffset.UTC),
                includeDetail ? event.getDetail() : null);
    }
}
