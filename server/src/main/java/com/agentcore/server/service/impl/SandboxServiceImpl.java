package com.agentcore.server.service.impl;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.dto.sandbox.SandboxExecRequest;
import com.agentcore.server.service.SandboxService;
import org.springframework.stereotype.Service;

/**
 * Stub implementation; the Docker executor lands in M1 (FR-3).
 */
@Service
public class SandboxServiceImpl implements SandboxService {

    @Override
    public void exec(SandboxExecRequest request) {
        throw new ApiException(ErrorCode.NOT_IMPLEMENTED,
                "sandbox executor lands in M1 (requirements.md FR-3)");
    }
}
