package com.agentcore.server.common.sandbox;

/**
 * Terminal facts of one container run. {@code stdout}/{@code stderr} are the
 * capped text that was also streamed to the sink, so the audit row and the
 * model see the same bytes.
 */
public record SandboxRunOutcome(
        int exitCode,
        long durationMs,
        boolean timedOut,
        boolean truncated,
        boolean oomKilled,
        Long peakMemoryBytes,
        Long cpuTimeMs,
        String stdout,
        String stderr) {}
