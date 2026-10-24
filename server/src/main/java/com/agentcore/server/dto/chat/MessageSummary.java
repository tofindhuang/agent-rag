package com.agentcore.server.dto.chat;

import com.fasterxml.jackson.databind.JsonNode;
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
        Instant createdAt,
        /** Tool-specific JSON (kb_search citations); null for old or non-tool rows. */
        JsonNode details) {}
