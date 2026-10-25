package com.agentcore.server.dto.admin;

import com.agentcore.server.dto.usage.UsageDaily;
import com.agentcore.server.dto.usage.UsageTotals;
import com.agentcore.server.dto.usage.UsageWindow;
import java.util.List;

/** Admin user drill-down: daily usage, model split and session metadata. */
public record AdminUserDetailResponse(
        String currency,
        AdminUserInfo user,
        UsageTotals today,
        UsageWindow window,
        List<UsageDaily> daily,
        List<AdminUserModel> models,
        AdminUserSessions sessions) {}
