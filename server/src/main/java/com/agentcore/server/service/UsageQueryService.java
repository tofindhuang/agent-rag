package com.agentcore.server.service;

import com.agentcore.server.dto.usage.UserUsageResponse;

/** Read-only usage aggregation shared by the user and admin surfaces (ADR-0023). */
public interface UsageQueryService {

    UserUsageResponse userUsage(long userId, int days);
}
