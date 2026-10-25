package com.agentcore.server.service;

import com.agentcore.server.dto.admin.AdminOverviewResponse;
import com.agentcore.server.dto.admin.AdminUserDetailResponse;
import com.agentcore.server.dto.admin.AdminUserListResponse;
import com.agentcore.server.dto.usage.UserUsageResponse;

/** Read-only usage aggregation shared by the user and admin surfaces (ADR-0023). */
public interface UsageQueryService {

    UserUsageResponse userUsage(long userId, int days);

    AdminOverviewResponse adminOverview(int days);

    AdminUserListResponse adminUsers(String query, int page, int size, int days, String sort);

    AdminUserDetailResponse adminUser(long userId, int days, int page, int size);
}
