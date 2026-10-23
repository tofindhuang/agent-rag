package com.agentcore.server.controller;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.dto.sandbox.SandboxExecRequest;
import com.agentcore.server.service.SandboxService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stub for the run_code tool callback (requirements.md FR-3).
 * The Docker executor lands in M1; see sandbox/Dockerfile.python for the
 * isolation flags the executor must apply.
 */
@RestController
@RequestMapping("/api/exec")
public class SandboxController {

    private final SandboxService sandboxService;

    public SandboxController(SandboxService sandboxService) {
        this.sandboxService = sandboxService;
    }

    @PostMapping
    public void exec(@RequestBody SandboxExecRequest request) {
        if (request.code() == null || request.code().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "code is required");
        }
        sandboxService.exec(request);
    }
}
