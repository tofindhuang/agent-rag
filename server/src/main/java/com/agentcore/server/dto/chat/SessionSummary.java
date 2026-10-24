package com.agentcore.server.dto.chat;

import java.time.Instant;

public record SessionSummary(
        String id,
        String title,
        String modelLabel,
        String modelSource,
        String kbId,
        String kbName,
        Instant lastActiveAt,
        Instant createdAt) {}
