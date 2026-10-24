package com.agentcore.server.common.sandbox;

/** Concurrency admission for run_code (FR-3, AC4). */
public interface SandboxAdmission {

    /**
     * Blocks until a per-user and a global permit are both available, or the
     * queue budget expires.
     *
     * @throws com.agentcore.server.common.error.ApiException
     *         {@code SANDBOX_QUEUE_TIMEOUT} or {@code UPSTREAM_UNAVAILABLE}
     */
    SandboxPermit acquire(long userId);
}
