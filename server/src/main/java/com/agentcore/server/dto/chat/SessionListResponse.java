package com.agentcore.server.dto.chat;

import java.util.List;

public record SessionListResponse(List<SessionSummary> items, String nextCursor) {}
