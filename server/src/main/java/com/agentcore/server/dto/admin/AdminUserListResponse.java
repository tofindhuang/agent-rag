package com.agentcore.server.dto.admin;

import java.util.List;

/** Paginated admin user list. */
public record AdminUserListResponse(
        String currency, List<AdminUserSummary> items, long total, int page, int size) {}
