package com.agentcore.server.dto.chat;

import java.time.Instant;

public record SessionSummary(String id, String title, Instant lastActiveAt, Instant createdAt) {}
