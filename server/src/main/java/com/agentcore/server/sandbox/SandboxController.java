package com.agentcore.server.sandbox;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stub for the run_code tool callback (requirements.md FR-3).
 * The Docker executor lands in M1; see sandbox/Dockerfile.python for the
 * isolation flags the executor must apply.
 */
@RestController
@RequestMapping("/api/exec")
public class SandboxController {

    public record ExecRequest(String language, String code, Integer timeoutMs) {}

    @PostMapping
    public Map<String, Object> exec(@RequestBody ExecRequest request) {
        if (request.code() == null || request.code().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "code is required");
        }
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                "sandbox executor lands in M1 (requirements.md FR-3)");
    }
}
