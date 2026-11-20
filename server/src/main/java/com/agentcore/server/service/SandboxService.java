package com.agentcore.server.service;

import com.agentcore.server.common.identity.ToolIdentity;
import com.agentcore.server.common.sandbox.CancellationToken;
import com.agentcore.server.common.sandbox.SandboxEventSink;
import com.agentcore.server.dto.sandbox.SandboxExecRequest;
import com.agentcore.server.dto.sandbox.SandboxExecution;

/**
 * Sandbox execution use cases. {@link #start} validates and admits the request
 * (errors surface as JSON before SSE); {@link #run} executes it on the sandbox
 * executor thread and streams frames to the sink.
 */
public interface SandboxService {

    SandboxExecution start(SandboxExecRequest request, ToolIdentity identity);

    void run(SandboxExecution execution, SandboxEventSink sink, CancellationToken cancellation);

    /** Releases admission and marks the audit row when the executor rejects the task. */
    void abandon(SandboxExecution execution, String reason);
}
