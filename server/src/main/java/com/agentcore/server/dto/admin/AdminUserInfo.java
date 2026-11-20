package com.agentcore.server.dto.admin;

import java.time.Instant;

/** Identity and activity summary for one user. */
public record AdminUserInfo(
        long userId,
        String email,
        Instant createdAt,
        Instant lastActiveAt,
        long sessions) {}
