package com.agentcore.server.common.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis-backed view of the gateway registry and session routes (ADR-0024). */
@Component
public class RedisGatewayDirectory implements GatewayDirectory {

    static final String INSTANCES_KEY = "gw:instances";
    static final String INSTANCE_KEY_PREFIX = "gw:inst:";
    static final String ROUTE_KEY_PREFIX = "route:sess:";
    private static final Duration ROUTE_TTL = Duration.ofHours(24);
    private static final Logger log = LoggerFactory.getLogger(RedisGatewayDirectory.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisGatewayDirectory(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<GatewayInstance> instances() {
        Set<String> ids = redis.opsForSet().members(INSTANCES_KEY);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<GatewayInstance> instances = new ArrayList<>();
        // LinkedHashSet keeps the registry order stable for deterministic tie-breaks.
        for (String id : new LinkedHashSet<>(ids)) {
            String raw = redis.opsForValue().get(INSTANCE_KEY_PREFIX + id);
            if (raw == null) continue;
            try {
                JsonNode node = objectMapper.readTree(raw);
                String url = node.path("url").asText("");
                if (url.isBlank()) continue;
                instances.add(new GatewayInstance(
                        id,
                        stripTrailingSlash(url),
                        node.path("sessions").asInt(0),
                        node.path("maxSessions").asInt(0),
                        node.path("draining").asBoolean(false)));
            } catch (Exception ex) {
                log.warn("ignoring unreadable gateway instance {}: {}", id, ex.getMessage());
            }
        }
        return instances;
    }

    @Override
    public String route(String sessionId) {
        return redis.opsForValue().get(ROUTE_KEY_PREFIX + sessionId);
    }

    @Override
    public void saveRoute(String sessionId, String instanceId) {
        redis.opsForValue().set(ROUTE_KEY_PREFIX + sessionId, instanceId, ROUTE_TTL);
    }

    @Override
    public void deleteRoute(String sessionId) {
        redis.delete(ROUTE_KEY_PREFIX + sessionId);
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
