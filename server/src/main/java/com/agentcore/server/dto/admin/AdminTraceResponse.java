package com.agentcore.server.dto.admin;

import java.util.List;

/** Admin trace payload: one run summary plus its ordered timeline. */
public record AdminTraceResponse(
        String currency, AdminTraceSummary summary, List<AdminTraceNode> timeline) {}
