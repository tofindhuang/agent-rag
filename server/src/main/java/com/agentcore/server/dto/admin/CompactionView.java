package com.agentcore.server.dto.admin;

/** Compaction count and token deltas inside the window. */
public record CompactionView(long count, long tokensBefore, long tokensAfter) {}
