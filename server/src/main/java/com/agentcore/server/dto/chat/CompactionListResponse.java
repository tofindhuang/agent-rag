package com.agentcore.server.dto.chat;

import java.util.List;

/**
 * Compaction summaries of one session, oldest first, capped at 50; {@code total}
 * lets the UI say when older ones are hidden (ADR-0028).
 */
public record CompactionListResponse(List<CompactionSummary> items, long total) {}
