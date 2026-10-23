package com.agentcore.server.dto.chat;

import java.time.Instant;

public record MessageSummary(
        Long id,
        String turnId,
        Integer ordinal,
        String role,
        String content,
        String thinking,
        String status,
        String deliveryStatus,
        Instant createdAt) {}
