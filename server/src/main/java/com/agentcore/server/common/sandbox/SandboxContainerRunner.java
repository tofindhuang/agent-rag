package com.agentcore.server.common.sandbox;

/**
 * Port for the Docker interaction. Tests replace it with a fake; the service
 * never depends on docker-java types.
 */
public interface SandboxContainerRunner {

    /** Fails fast when Docker or the configured image is unavailable. */
    void ensureAvailable();

    SandboxRunOutcome run(SandboxRunSpec spec, SandboxEventSink sink, CancellationToken cancellation);
}
