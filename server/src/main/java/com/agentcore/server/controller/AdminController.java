package com.agentcore.server.controller;

import com.agentcore.server.common.auth.AdminGuard;
import com.agentcore.server.common.auth.AuthenticatedUser;
import com.agentcore.server.common.usage.UsageWindows;
import com.agentcore.server.dto.admin.AdminOverviewResponse;
import com.agentcore.server.dto.admin.AdminPingResponse;
import com.agentcore.server.service.UsageQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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

    public AdminController(AdminGuard adminGuard, UsageQueryService usageQueryService) {
        this.adminGuard = adminGuard;
        this.usageQueryService = usageQueryService;
    }

    @GetMapping("/ping")
    public AdminPingResponse ping(@AuthenticationPrincipal AuthenticatedUser user) {
        adminGuard.requireAdmin(user.userId());
        return new AdminPingResponse(AdminGuard.ROLE_ADMIN);
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
}
