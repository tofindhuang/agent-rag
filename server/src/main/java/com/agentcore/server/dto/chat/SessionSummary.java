package com.agentcore.server.dto.chat;

import java.time.Instant;

public record SessionSummary(
        String id,
        String title,
        String modelLabel,
        String modelSource,
        Instant lastActiveAt,
        Instant createdAt) {}
