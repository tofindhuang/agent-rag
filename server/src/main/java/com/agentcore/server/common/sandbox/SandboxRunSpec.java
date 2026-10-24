package com.agentcore.server.common.sandbox;

/** Everything the container runner needs for one execution. */
public record SandboxRunSpec(String execId, String code, int timeoutMs) {}
