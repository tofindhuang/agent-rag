package com.agentcore.server.common.gateway;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.config.GatewayProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Places and resolves sessions onto gateway instances (ADR-0024).
 *
 * <p>The Redis registry is the primary source; the static seed list and a
 * short-lived local cache keep routing usable while Redis is unavailable. The
 * gateway-side file lock remains the authoritative single-writer guard, so a
 * stale route never grants concurrent writes — it only costs one retry.
 */
@Component
public class GatewayRouter {

    private static final long ROUTE_CACHE_MS = 5_000;
    private static final long INSTANCE_CACHE_MS = 2_000;
    private static final long UNAVAILABLE_MS = 5_000;
    private static final Logger log = LoggerFactory.getLogger(GatewayRouter.class);

    private final GatewayProperties properties;
    private final GatewayDirectory directory;
    private final Map<String, CachedRoute> routes = new ConcurrentHashMap<>();
    private final Map<String, Long> unavailableUntil = new ConcurrentHashMap<>();
    private final List<GatewayInstance> seeds;
    private volatile CachedInstances cachedInstances;

    public GatewayRouter(GatewayProperties properties, GatewayDirectory directory) {
        this.properties = properties;
        this.directory = directory;
        List<GatewayInstance> seedList = new ArrayList<>();
        List<String> urls = properties.seedUrls();
        for (int i = 0; i < urls.size(); i++) {
            seedList.add(new GatewayInstance(
                    "seed-" + i, urls.get(i), 0, Integer.MAX_VALUE, false));
        }
        this.seeds = List.copyOf(seedList);
    }

    /** Resolves the instance that should serve this session. */
    public SessionTarget resolve(String sessionId) {
        long now = System.currentTimeMillis();
        CachedRoute cached = routes.get(sessionId);
        if (cached != null && cached.expiresAt > now) {
            return cached.target;
        }
        try {
            String instanceId = directory.route(sessionId);
            if (instanceId != null) {
                GatewayInstance instance = find(instanceId);
                if (instance != null && !instance.draining()) {
                    return remember(sessionId, new SessionTarget(instanceId, instance.baseUrl()));
                }
            }
        } catch (RuntimeException ex) {
            log.warn("gateway route lookup failed: {}", ex.getMessage());
        }
        return place(sessionId);
    }

    /** Resolves a specific instance, including one that is draining. */
    public SessionTarget targetFor(String instanceId) {
        GatewayInstance instance = find(instanceId);
        return instance == null
                ? null
                : new SessionTarget(instance.instanceId(), instance.baseUrl());
    }

    /** Chooses a new instance for the session and pins it when Redis allows. */
    public SessionTarget place(String sessionId) {
        List<GatewayInstance> instances = routableInstances();
        if (instances.isEmpty()) {
            throw new ApiException(ErrorCode.GATEWAY_CAPACITY,
                    "no gateway instance has capacity");
        }
        // Hash placement keeps new sessions evenly spread even while the
        // registry's session counts are up to one heartbeat stale. Least-loaded
        // placement would pile every concurrent create onto the same instance.
        GatewayInstance chosen =
                instances.get(Math.floorMod(sessionId.hashCode(), instances.size()));
        SessionTarget target = new SessionTarget(chosen.instanceId(), chosen.baseUrl());
        remember(sessionId, target);
        saveRoute(sessionId, chosen.instanceId());
        return target;
    }

    /** Corrects a stale route after the gateway reported the lock owner. */
    public void correct(String sessionId, String instanceId) {
        try {
            GatewayInstance instance = find(instanceId);
            if (instance != null && !instance.draining()) {
                SessionTarget target = new SessionTarget(instanceId, instance.baseUrl());
                remember(sessionId, target);
                saveRoute(sessionId, instanceId);
                return;
            }
        } catch (RuntimeException ex) {
            log.warn("gateway route correction failed: {}", ex.getMessage());
        }
        failover(sessionId);
    }

    /** Drops the current route and briefly avoids the instance that failed. */
    public void failover(String sessionId) {
        failover(sessionId, true);
    }

    /**
     * Drops the route without penalizing a healthy-but-full or draining
     * instance. Penalizing those would cascade until every instance looks
     * unavailable.
     */
    public void failover(String sessionId, boolean penalizeInstance) {
        CachedRoute cached = routes.remove(sessionId);
        if (penalizeInstance && cached != null) {
            unavailableUntil.put(cached.target.instanceId(),
                    System.currentTimeMillis() + UNAVAILABLE_MS);
        }
        deleteRoute(sessionId);
    }

    /** Forgets the route without blaming the instance (session deleted/purged). */
    public void forget(String sessionId) {
        routes.remove(sessionId);
        deleteRoute(sessionId);
    }

    /** Marks an instance unavailable for a short period after a transport failure. */
    public void markUnavailable(String instanceId) {
        unavailableUntil.put(instanceId, System.currentTimeMillis() + UNAVAILABLE_MS);
    }

    private List<GatewayInstance> routableInstances() {
        List<GatewayInstance> source = null;
        try {
            List<GatewayInstance> all = directory.instances();
            if (!all.isEmpty()) {
                cachedInstances = new CachedInstances(all, System.currentTimeMillis() + INSTANCE_CACHE_MS);
                source = all;
            }
        } catch (RuntimeException ex) {
            log.warn("gateway registry unavailable: {}", ex.getMessage());
        }
        if (source == null) {
            CachedInstances cached = cachedInstances;
            source = cached != null && cached.expiresAt > System.currentTimeMillis()
                    ? cached.instances
                    : seeds;
        }
        long now = System.currentTimeMillis();
        unavailableUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        List<GatewayInstance> available = source.stream()
                .filter(instance -> !instance.draining() && instance.hasCapacity())
                .toList();
        List<GatewayInstance> healthy = available.stream()
                .filter(instance -> unavailableUntil.getOrDefault(instance.instanceId(), 0L) <= now)
                .toList();
        if (!healthy.isEmpty()) {
            return healthy;
        }
        // A short cooldown must not turn into a hard capacity failure when the
        // only remaining instances are the ones that just restarted. The file
        // lock still protects correctness, so prefer capacity over cooldown.
        if (!available.isEmpty()) {
            return available;
        }
        log.warn("no gateway instance with capacity: {}",
                source.stream()
                        .map(instance -> instance.instanceId() + "=" + instance.sessions()
                                + "/" + instance.maxSessions()
                                + (instance.draining() ? "/draining" : ""))
                        .toList());
        return List.of();
    }

    private GatewayInstance find(String instanceId) {
        return instances().stream()
                .filter(instance -> instance.instanceId().equals(instanceId))
                .findFirst()
                .orElse(null);
    }

    private List<GatewayInstance> instances() {
        try {
            List<GatewayInstance> all = directory.instances();
            if (!all.isEmpty()) {
                cachedInstances = new CachedInstances(all, System.currentTimeMillis() + INSTANCE_CACHE_MS);
                return all;
            }
        } catch (RuntimeException ex) {
            log.warn("gateway registry unavailable: {}", ex.getMessage());
        }
        CachedInstances cached = cachedInstances;
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
            return cached.instances;
        }
        return seeds;
    }

    private SessionTarget remember(String sessionId, SessionTarget target) {
        routes.put(sessionId, new CachedRoute(target, System.currentTimeMillis() + ROUTE_CACHE_MS));
        return target;
    }

    private void saveRoute(String sessionId, String instanceId) {
        try {
            directory.saveRoute(sessionId, instanceId);
        } catch (RuntimeException ex) {
            log.warn("gateway route save failed: {}", ex.getMessage());
        }
    }

    private void deleteRoute(String sessionId) {
        try {
            directory.deleteRoute(sessionId);
        } catch (RuntimeException ex) {
            log.warn("gateway route delete failed: {}", ex.getMessage());
        }
    }

    private record CachedRoute(SessionTarget target, long expiresAt) {}

    private record CachedInstances(List<GatewayInstance> instances, long expiresAt) {}
}
