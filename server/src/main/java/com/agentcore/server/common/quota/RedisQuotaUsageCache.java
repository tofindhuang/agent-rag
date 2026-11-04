package com.agentcore.server.common.quota;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis-backed cache; read/write failures degrade to a cache miss. */
@Component
public class RedisQuotaUsageCache implements QuotaUsageCache {

    private final StringRedisTemplate redis;

    public RedisQuotaUsageCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public String get(String key) {
        return redis.opsForValue().get(key);
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        redis.opsForValue().set(key, value, ttl);
    }
}
