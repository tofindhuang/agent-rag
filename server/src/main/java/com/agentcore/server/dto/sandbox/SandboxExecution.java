package com.agentcore.server.dto.sandbox;

/**
 * Validated execution admitted by the service before the SSE stream starts.
 * The controller uses this to avoid starting a stream for rejected requests.
 */
public record SandboxExecution(
        String execId,
        String code,
        String language,
        int timeoutMs,
        long userId,
        String sessionId,
        String turnId,
        String runId,
        String toolCallId,
        String requestId) {}
