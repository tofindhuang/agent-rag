package com.agentcore.server.common.gateway;

/** One live gateway instance as seen by the Java router. */
public record GatewayInstance(
        String instanceId,
        String baseUrl,
        int sessions,
        int maxSessions,
        boolean draining) {

    public boolean hasCapacity() {
        return maxSessions > 0 && sessions < maxSessions;
    }

    public double load() {
        return maxSessions <= 0 ? 1.0 : (double) sessions / maxSessions;
    }
}
