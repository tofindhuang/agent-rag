package com.agentcore.server.controller;

import com.agentcore.server.common.auth.AuthenticatedUser;
import com.agentcore.server.dao.AuditEventDao;
import com.agentcore.server.domain.AuditEvent;
import com.agentcore.server.dto.admin.AuditEventView;
import com.agentcore.server.dto.admin.AuditPage;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Self-service security events (ADR-0033): the caller's own audit trail only. */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private static final int MAX_LIMIT = 100;

    private final AuditEventDao auditEventDao;

    public AuditController(AuditEventDao auditEventDao) {
        this.auditEventDao = auditEventDao;
    }

    @GetMapping("/me")
    public AuditPage mine(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) Long beforeId) {
        int capped = Math.min(Math.max(limit, 1), MAX_LIMIT);
        List<AuditEvent> rows =
                auditEventDao.listByActor(user.userId(), beforeId, capped);
        return new AuditPage(
                rows.stream().map((row) -> view(row, false)).toList(),
                rows.size() < capped ? null : rows.get(rows.size() - 1).getId());
    }

    private static AuditEventView view(AuditEvent event, boolean includeDetail) {
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
