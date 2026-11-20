package com.agentcore.server.common.gateway;

/** Resolved gateway instance for one session-level request. */
public record SessionTarget(String instanceId, String baseUrl) {}
