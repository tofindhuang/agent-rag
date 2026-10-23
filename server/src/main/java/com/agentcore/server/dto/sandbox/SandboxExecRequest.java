package com.agentcore.server.dto.sandbox;

public record SandboxExecRequest(String language, String code, Integer timeoutMs) {}
