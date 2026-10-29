package com.agentcore.server.dto.chat;

import java.time.Instant;

/** One compaction summary card (ADR-0028); session content, never served to admin. */
public record CompactionSummary(
        Long id,
        Long afterMessageId,
        String reason,
        String summary,
        boolean truncated,
        Long tokensBefore,
        Long tokensAfter,
        Instant createdAt) {}
