package com.agentcore.server.dto.usage;

import java.util.List;

/** Self-service usage payload for the settings page. */
public record UserUsageResponse(
        String currency,
        UsageTotals today,
        UsageWindow window,
        List<UsageDaily> daily,
        List<UsageTopSession> topSessions) {}
