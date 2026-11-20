package com.agentcore.server.dto.admin;

import java.util.List;

/** Deployment-wide observability payload (FR-7, ADR-0023). */
public record AdminOverviewResponse(
        int days,
        String currency,
        ActiveView active,
        UsageAmount usage,
        ExecutionView executions,
        RetrievalView retrieval,
        CompactionView compactions,
        SandboxView sandbox,
        List<AdminDaily> daily) {}
