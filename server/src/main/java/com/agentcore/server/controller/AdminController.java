package com.agentcore.server.controller;

import com.agentcore.server.common.auth.AdminGuard;
import com.agentcore.server.common.auth.AuthenticatedUser;
import com.agentcore.server.dto.admin.AdminPingResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only admin namespace (ADR-0023); every endpoint checks the database role. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminGuard adminGuard;

    public AdminController(AdminGuard adminGuard) {
        this.adminGuard = adminGuard;
    }

    @GetMapping("/ping")
    public AdminPingResponse ping(@AuthenticationPrincipal AuthenticatedUser user) {
        adminGuard.requireAdmin(user.userId());
        return new AdminPingResponse(AdminGuard.ROLE_ADMIN);
    }
}
