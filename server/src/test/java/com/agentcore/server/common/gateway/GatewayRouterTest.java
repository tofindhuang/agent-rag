package com.agentcore.server.common.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.config.GatewayProperties;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GatewayRouterTest {

    private static final class FakeDirectory implements GatewayDirectory {
        List<GatewayInstance> instances = new ArrayList<>();
        final Map<String, String> routes = new HashMap<>();
        boolean fail;

        @Override
        public List<GatewayInstance> instances() {
            if (fail) throw new IllegalStateException("redis unavailable");
            return instances;
        }

        @Override
        public String route(String sessionId) {
            if (fail) throw new IllegalStateException("redis unavailable");
            return routes.get(sessionId);
        }

        @Override
        public void saveRoute(String sessionId, String instanceId) {
            if (fail) throw new IllegalStateException("redis unavailable");
            routes.put(sessionId, instanceId);
        }

        @Override
        public void deleteRoute(String sessionId) {
            if (fail) throw new IllegalStateException("redis unavailable");
            routes.remove(sessionId);
        }
    }

    private static GatewayRouter router(FakeDirectory directory, String urls) {
        return new GatewayRouter(
                new GatewayProperties("http://seed-single", null, urls), directory);
    }

    @Test
    void placesNewSessionsOnTheLeastLoadedInstance() {
        FakeDirectory directory = new FakeDirectory();
        directory.instances = List.of(
                new GatewayInstance("g1", "http://g1", 30, 60, false),
                new GatewayInstance("g2", "http://g2", 3, 60, false));
        GatewayRouter router = router(directory, null);

        SessionTarget target = router.resolve("session-1");

        assertEquals("g2", target.instanceId());
        assertEquals("http://g2", target.baseUrl());
        assertEquals("g2", directory.routes.get("session-1"));
    }

    @Test
    void honorsAnExistingRoute() {
        FakeDirectory directory = new FakeDirectory();
        directory.instances = List.of(
                new GatewayInstance("g1", "http://g1", 30, 60, false),
                new GatewayInstance("g2", "http://g2", 3, 60, false));
        directory.routes.put("session-1", "g1");
        GatewayRouter router = router(directory, null);

        assertEquals("g1", router.resolve("session-1").instanceId());
    }

    @Test
    void replacesARouteWhoseInstanceDisappeared() {
        FakeDirectory directory = new FakeDirectory();
        directory.instances = List.of(new GatewayInstance("g2", "http://g2", 3, 60, false));
        directory.routes.put("session-1", "g1");
        GatewayRouter router = router(directory, null);

        assertEquals("g2", router.resolve("session-1").instanceId());
        assertEquals("g2", directory.routes.get("session-1"));
    }

    @Test
    void refusesToPlaceWhenEveryInstanceIsDraining() {
        FakeDirectory directory = new FakeDirectory();
        directory.instances = List.of(
                new GatewayInstance("g1", "http://g1", 0, 60, true),
                new GatewayInstance("g2", "http://g2", 0, 60, true));
        GatewayRouter router = router(directory, null);

        ApiException failure = assertThrows(ApiException.class, () -> router.place("session-1"));
        assertEquals(ErrorCode.GATEWAY_CAPACITY, failure.code());
    }

    @Test
    void refusesToPlaceWhenEveryInstanceIsFull() {
        FakeDirectory directory = new FakeDirectory();
        directory.instances = List.of(
                new GatewayInstance("g1", "http://g1", 60, 60, false),
                new GatewayInstance("g2", "http://g2", 60, 60, false));
        GatewayRouter router = router(directory, null);

        ApiException failure = assertThrows(ApiException.class, () -> router.place("session-1"));
        assertEquals(ErrorCode.GATEWAY_CAPACITY, failure.code());
    }

    @Test
    void fallsBackToStaticSeedsWhenRedisIsUnavailable() {
        FakeDirectory directory = new FakeDirectory();
        directory.fail = true;
        GatewayRouter router = router(directory, "http://a,http://b");

        SessionTarget target = router.resolve("session-1");
        assertEquals(target, router.resolve("session-1"));
        assertTrue(target.baseUrl().equals("http://a") || target.baseUrl().equals("http://b"));

        router.failover("session-1");
        SessionTarget moved = router.resolve("session-1");
        assertNotEquals(target.instanceId(), moved.instanceId());
    }

    @Test
    void correctsTheRouteToTheLockOwner() {
        FakeDirectory directory = new FakeDirectory();
        directory.instances = List.of(
                new GatewayInstance("g1", "http://g1", 1, 60, false),
                new GatewayInstance("g2", "http://g2", 50, 60, false));
        GatewayRouter router = router(directory, null);

        router.correct("session-1", "g2");

        assertEquals("g2", router.resolve("session-1").instanceId());
        assertEquals("g2", directory.routes.get("session-1"));
    }
}
