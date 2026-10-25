package com.agentcore.server.common.gateway;

import java.util.List;

/**
 * Registry boundary for gateway instances and session routes.
 *
 * <p>Reads may throw when Redis is unavailable; the router treats that as a
 * degraded registry and falls back to cached instances or static seeds.
 */
public interface GatewayDirectory {

    List<GatewayInstance> instances();

    /** Returns the instance id currently pinned to the session, or null. */
    String route(String sessionId);

    void saveRoute(String sessionId, String instanceId);

    void deleteRoute(String sessionId);
}
