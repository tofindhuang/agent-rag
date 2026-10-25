package com.agentcore.server.dto.admin;

import java.util.List;

/** Paginated session list for one user. */
public record AdminUserSessions(
        List<AdminUserSession> items, long total, int page, int size) {}
