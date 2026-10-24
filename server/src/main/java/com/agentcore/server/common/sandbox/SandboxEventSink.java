package com.agentcore.server.common.sandbox;

/**
 * Downstream for run_code frames. Implementations must tolerate being called
 * after the client disconnected; the executor keeps draining the container so
 * it can finish the audit row.
 */
public interface SandboxEventSink {

    void started(String execId);

    void phase(String phase);

    void stdout(String chunk);

    void stderr(String chunk);

    void result(SandboxExecResult result);
}
