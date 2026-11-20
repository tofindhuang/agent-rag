package com.agentcore.server.controller;

import com.agentcore.server.common.auth.AuthenticatedUser;
import com.agentcore.server.common.usage.UsageWindows;
import com.agentcore.server.dto.usage.UserUsageResponse;
import com.agentcore.server.service.UsageQueryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Self-service usage for the authenticated user (ADR-0023). */
@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final UsageQueryService usageQueryService;

    public UsageController(UsageQueryService usageQueryService) {
        this.usageQueryService = usageQueryService;
    }

    @GetMapping("/me")
    public UserUsageResponse me(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(value = "window", defaultValue = "30d") String window) {
        return usageQueryService.userUsage(user.userId(), UsageWindows.days(window));
    }
}
