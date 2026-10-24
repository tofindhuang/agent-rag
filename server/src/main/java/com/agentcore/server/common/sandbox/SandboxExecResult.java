package com.agentcore.server.common.sandbox;

/** One execution result sent to the gateway as the unique {@code result} frame. */
public record SandboxExecResult(int exitCode, long durationMs, boolean timedOut, boolean truncated) {}
