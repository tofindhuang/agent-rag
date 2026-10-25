package com.agentcore.server.dto.admin;

/** Sandbox timing percentiles; null when there are no samples. */
public record SandboxView(
        long executions,
        Long queueP50Ms,
        Long queueP95Ms,
        Long durationP50Ms,
        Long durationP95Ms) {}
