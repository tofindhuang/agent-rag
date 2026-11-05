package com.agentcore.server.controller;

import com.agentcore.server.common.auth.AdminGuard;
import com.agentcore.server.dao.AuditEventDao;
import com.agentcore.server.domain.AuditEvent;
import com.agentcore.server.dto.admin.AuditEventView;
import com.agentcore.server.dto.admin.AuditPage;
import com.agentcore.server.common.auth.AuthenticatedUser;
import com.agentcore.server.common.usage.UsageWindows;
import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.dto.admin.AdminOverviewResponse;
import com.agentcore.server.dto.admin.AdminPingResponse;
import com.agentcore.server.dto.admin.AdminTraceResponse;
import com.agentcore.server.dto.admin.AdminUserDetailResponse;
import com.agentcore.server.dto.admin.AdminUserListResponse;
import com.agentcore.server.service.AdminTraceService;
import com.agentcore.server.service.UsageQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only admin namespace (ADR-0023); every endpoint checks the database role. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final AdminGuard adminGuard;
    private final UsageQueryService usageQueryService;
    private final AdminTraceService traceService;
    private final AuditEventDao auditEventDao;

    public AdminController(
            AdminGuard adminGuard,
            UsageQueryService usageQueryService,
            AdminTraceService traceService,
            AuditEventDao auditEventDao) {
        this.adminGuard = adminGuard;
        this.usageQueryService = usageQueryService;
        this.traceService = traceService;
        this.auditEventDao = auditEventDao;
    }

    @GetMapping("/ping")
    public AdminPingResponse ping(@AuthenticationPrincipal AuthenticatedUser user) {
        adminGuard.requireAdmin(user.userId());
        return new AdminPingResponse(AdminGuard.ROLE_ADMIN);
    }

    /** Read-only audit trail (ADR-0033); metadata only, newest first. */
    @GetMapping("/audit")
    public AuditPage audit(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false) String action) {
        adminGuard.requireAdmin(user.userId());
        int capped = Math.min(Math.max(limit, 1), 200);
        java.util.List<AuditEvent> rows = auditEventDao.listAll(action, beforeId, capped);
        return new AuditPage(
                rows.stream().map(AdminController::auditView).toList(),
                rows.size() < capped ? null : rows.get(rows.size() - 1).getId());
    }

    private static AuditEventView auditView(AuditEvent event) {
        return new AuditEventView(
                event.getId(),
                event.getActorUserId(),
                event.getActorType(),
                event.getAction(),
                event.getTargetType(),
                event.getTargetId(),
                event.getOutcome(),
                event.getIp(),
                event.getCreatedAt() == null
                        ? null
                        : event.getCreatedAt().toInstant(java.time.ZoneOffset.UTC),
                event.getDetail());
    }

    @GetMapping("/overview")
    public AdminOverviewResponse overview(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(value = "window", defaultValue = "7d") String window) {
        adminGuard.requireAdmin(user.userId());
        int days = UsageWindows.days(window);
        log.info("admin read: admin={} endpoint=overview window={}d", user.userId(), days);
        return usageQueryService.adminOverview(days);
    }

    @GetMapping("/users")
    public AdminUserListResponse users(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(value = "query", defaultValue = "") String query,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "window", defaultValue = "7d") String window,
            @RequestParam(value = "sort", defaultValue = "lastActive") String sort) {
        adminGuard.requireAdmin(user.userId());
        int days = UsageWindows.days(window);
        int safePage = page(page);
        int safeSize = size(size);
        String safeSort = switch (sort) {
            case "lastActive", "cost", "tokens" -> sort;
            default -> throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "sort must be lastActive, cost or tokens");
        };
        log.info("admin read: admin={} endpoint=users window={}d query={}",
                user.userId(), days, query.isBlank() ? "-" : "set");
        return usageQueryService.adminUsers(query, safePage, safeSize, days, safeSort);
    }

    @GetMapping("/users/{userId}")
    public AdminUserDetailResponse user(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable("userId") long userId,
            @RequestParam(value = "window", defaultValue = "7d") String window,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        adminGuard.requireAdmin(user.userId());
        int days = UsageWindows.days(window);
        log.info("admin read: admin={} endpoint=user target={}", user.userId(), userId);
        return usageQueryService.adminUser(userId, days, page(page), size(size));
    }

    @GetMapping("/trace")
    public AdminTraceResponse trace(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(value = "requestId", required = false) String requestId,
            @RequestParam(value = "turnId", required = false) String turnId,
            @RequestParam(value = "runId", required = false) String runId) {
        adminGuard.requireAdmin(user.userId());
        int provided = (blank(requestId) ? 0 : 1)
                + (blank(turnId) ? 0 : 1)
                + (blank(runId) ? 0 : 1);
        if (provided != 1) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "provide exactly one of requestId, turnId or runId");
        }
        log.info("admin read: admin={} endpoint=trace", user.userId());
        return traceService.trace(requestId, turnId, runId);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static int page(int page) {
        if (page < 1) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "page must be at least 1");
        }
        return page;
    }

    private static int size(int size) {
        if (size < 1 || size > 100) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "size must be between 1 and 100");
        }
        return size;
    }
}
