package com.agentcore.server.domain;

import java.util.List;

/** Ascending compaction summaries plus the session-wide total (ADR-0028). */
public record CompactionPage(List<SessionCompaction> items, long total) {}
