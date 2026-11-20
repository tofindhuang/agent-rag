package com.agentcore.server.dto.admin;

/** Terminal execution counts inside the window. */
public record ExecutionView(long total, long succeeded, long failed, long timedOut) {}
