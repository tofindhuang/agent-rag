package com.agentcore.server.service;

import com.agentcore.server.common.identity.ToolIdentity;
import com.agentcore.server.dto.sandbox.SandboxExecRequest;

/**
 * Sandbox execution use cases. Controllers depend on this interface; the
 * implementation lives in {@code service.impl}.
 */
public interface SandboxService {

    void exec(SandboxExecRequest request, ToolIdentity identity);
}
