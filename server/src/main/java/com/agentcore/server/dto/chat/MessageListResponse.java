package com.agentcore.server.dto.chat;

import java.util.List;

public record MessageListResponse(List<MessageSummary> items) {}
